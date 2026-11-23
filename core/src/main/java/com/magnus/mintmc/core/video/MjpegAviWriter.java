package com.magnus.mintmc.core.video;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Writes Motion JPEG frames into an AVI 1.0 file, streaming: every frame goes to disk as it
 * arrives and the header and index are completed on {@link #close()}. Plays in VLC and the
 * Windows media player, opens in common video editors, and converts to mp4 with
 * {@code ffmpeg -i clip.avi -c:v libx264 clip.mp4}.
 *
 * <p>AVI 1.0 keeps every size in 32 bits, so callers start a new file before
 * {@link #MAX_FILE_SIZE} is reached.
 */
public class MjpegAviWriter implements Closeable {

  /** Stays well below 2 GiB, the limit many players have for AVI 1.0 files. */
  public static final long MAX_FILE_SIZE = 1_900_000_000L;

  private static final int AVIF_HASINDEX = 0x10;
  private static final int AVIIF_KEYFRAME = 0x10;

  // Offsets of the fields completed on close
  private static final int RIFF_SIZE = 4;
  private static final int AVIH_MICROS_PER_FRAME = 32;
  private static final int AVIH_MAX_BYTES_PER_SEC = 36;
  private static final int AVIH_TOTAL_FRAMES = 48;
  private static final int AVIH_SUGGESTED_BUFFER = 60;
  private static final int STRH_LENGTH = 140;
  private static final int STRH_SUGGESTED_BUFFER = 144;
  private static final int MOVI_SIZE = 216;
  private static final int MOVI_FOURCC = 220;
  private static final int HEADER_SIZE = 224;

  private final RandomAccessFile file;
  private final int frameRate;
  private final byte[] chunkHeader = new byte[8];

  private int[] offsets = new int[1024];
  private int[] sizes = new int[1024];
  private int frames;
  private int largestFrame;
  private long position;
  private boolean closed;

  public MjpegAviWriter(Path path, int width, int height, int frameRate) throws IOException {
    this.file = new RandomAccessFile(path.toFile(), "rw");
    this.file.setLength(0);
    this.frameRate = frameRate;
    this.writeHeader(width, height);
  }

  /**
   * Appends one JPEG image as the next frame.
   */
  public void writeFrame(byte[] jpeg) throws IOException {
    if (this.frames == this.offsets.length) {
      this.offsets = Arrays.copyOf(this.offsets, this.frames * 2);
      this.sizes = Arrays.copyOf(this.sizes, this.frames * 2);
    }

    // idx1 offsets count from the 'movi' fourcc
    this.offsets[this.frames] = (int) (this.position - MOVI_FOURCC);
    this.sizes[this.frames] = jpeg.length;
    this.frames++;
    this.largestFrame = Math.max(this.largestFrame, jpeg.length);

    fourCC(this.chunkHeader, 0, "00dc");
    int32(this.chunkHeader, 4, jpeg.length);
    this.file.write(this.chunkHeader);
    this.file.write(jpeg);
    this.position += 8 + jpeg.length;
    if ((jpeg.length & 1) != 0) {
      this.file.write(0);
      this.position++;
    }
  }

  public int frames() {
    return this.frames;
  }

  /**
   * @return the bytes written so far
   */
  public long size() {
    return this.position + 8L + this.frames * 16L;
  }

  @Override
  public void close() throws IOException {
    if (this.closed) {
      return;
    }

    this.closed = true;
    try {
      long moviEnd = this.position;

      // The index, one entry per frame
      byte[] index = new byte[8 + this.frames * 16];
      fourCC(index, 0, "idx1");
      int32(index, 4, this.frames * 16);
      for (int frame = 0; frame < this.frames; frame++) {
        int offset = 8 + frame * 16;
        fourCC(index, offset, "00dc");
        int32(index, offset + 4, AVIIF_KEYFRAME);
        int32(index, offset + 8, this.offsets[frame]);
        int32(index, offset + 12, this.sizes[frame]);
      }

      this.file.write(index);
      long end = moviEnd + index.length;

      double seconds = Math.max(this.frames, 1) / (double) this.frameRate;
      long bytesPerSecond = (long) ((moviEnd - HEADER_SIZE) / seconds);

      this.patch(RIFF_SIZE, (int) (end - 8));
      this.patch(AVIH_MICROS_PER_FRAME, 1_000_000 / this.frameRate);
      this.patch(AVIH_MAX_BYTES_PER_SEC, (int) Math.min(bytesPerSecond, Integer.MAX_VALUE));
      this.patch(AVIH_TOTAL_FRAMES, this.frames);
      this.patch(AVIH_SUGGESTED_BUFFER, this.largestFrame + 8);
      this.patch(STRH_LENGTH, this.frames);
      this.patch(STRH_SUGGESTED_BUFFER, this.largestFrame + 8);
      this.patch(MOVI_SIZE, (int) (moviEnd - MOVI_FOURCC));
    } finally {
      this.file.close();
    }
  }

  private void patch(int offset, int value) throws IOException {
    byte[] bytes = new byte[4];
    int32(bytes, 0, value);
    this.file.seek(offset);
    this.file.write(bytes);
  }

  private void writeHeader(int width, int height) throws IOException {
    byte[] header = new byte[HEADER_SIZE];
    fourCC(header, 0, "RIFF");
    // 4: riff size, completed on close
    fourCC(header, 8, "AVI ");

    fourCC(header, 12, "LIST");
    int32(header, 16, 192); // hdrl size: "hdrl" + avih (64) + strl list (124)
    fourCC(header, 20, "hdrl");

    // Main AVI header
    fourCC(header, 24, "avih");
    int32(header, 28, 56);
    int32(header, 32, 1_000_000 / this.frameRate);
    // 36: max bytes per second, 40: padding granularity
    int32(header, 44, AVIF_HASINDEX);
    // 48: total frames, 52: initial frames
    int32(header, 56, 1); // streams
    // 60: suggested buffer size
    int32(header, 64, width);
    int32(header, 68, height);
    // 72 - 87: reserved

    fourCC(header, 88, "LIST");
    int32(header, 92, 116); // strl size: "strl" + strh (64) + strf (48)
    fourCC(header, 96, "strl");

    // Stream header
    fourCC(header, 100, "strh");
    int32(header, 104, 56);
    fourCC(header, 108, "vids");
    fourCC(header, 112, "MJPG");
    // 116: flags, 120: priority and language, 124: initial frames
    int32(header, 128, 1); // scale
    int32(header, 132, this.frameRate); // rate, frames per second is rate / scale
    // 136: start, 140: length, 144: suggested buffer size
    int32(header, 148, -1); // quality
    // 152: sample size
    int16(header, 156, 0);
    int16(header, 158, 0);
    int16(header, 160, width);
    int16(header, 162, height);

    // Stream format, a BITMAPINFOHEADER
    fourCC(header, 164, "strf");
    int32(header, 168, 40);
    int32(header, 172, 40);
    int32(header, 176, width);
    int32(header, 180, height);
    int16(header, 184, 1); // planes
    int16(header, 186, 24); // bit count
    fourCC(header, 188, "MJPG");
    int32(header, 192, width * height * 3);
    // 196 - 211: resolution and palette, unused

    fourCC(header, 212, "LIST");
    // 216: movi size, completed on close
    fourCC(header, 220, "movi");

    this.file.write(header);
    this.position = HEADER_SIZE;
  }

  private static void fourCC(byte[] target, int offset, String code) {
    for (int index = 0; index < 4; index++) {
      target[offset + index] = (byte) code.charAt(index);
    }
  }

  private static void int32(byte[] target, int offset, int value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
    target[offset + 2] = (byte) (value >>> 16);
    target[offset + 3] = (byte) (value >>> 24);
  }

  private static void int16(byte[] target, int offset, int value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
  }
}
