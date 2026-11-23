package com.magnus.mintmc.core.video;

import com.magnus.mintmc.core.capture.EncodedFrame;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Turns frames with irregular capture times into a video with a fixed frame rate. The game
 * doesn't render at an exact rate and frames can be dropped under load, so every output slot shows
 * the newest frame captured before it: frames are repeated when the game is slower than the video
 * and skipped when it is faster. That keeps the video in real time.
 *
 * <p>Starts a new part file before the AVI size limit is reached.
 */
public class ConstantRateWriter implements Closeable {

  private final Path basePath;
  private final int width;
  private final int height;
  private final int frameRate;
  private final long period;

  private MjpegAviWriter writer;
  private int part = 1;
  private long startNanos;
  private long slot;
  private EncodedFrame last;
  private long totalFrames;

  /**
   * @param basePath the file of the first part, later parts get a {@code _2}, {@code _3}... suffix
   */
  public ConstantRateWriter(Path basePath, int width, int height, int frameRate)
      throws IOException {
    this.basePath = basePath;
    this.width = width;
    this.height = height;
    this.frameRate = frameRate;
    this.period = 1_000_000_000L / frameRate;
    this.writer = new MjpegAviWriter(basePath, width, height, frameRate);
  }

  public void offer(EncodedFrame frame) throws IOException {
    if (!frame.sameSize(this.width, this.height)) {
      return;
    }

    if (this.last == null) {
      this.startNanos = frame.nanos();
      this.last = frame;
      return;
    }

    if (frame.nanos() < this.last.nanos()) {
      return;
    }

    this.fillUntil(frame.nanos());
    this.last = frame;
  }

  /**
   * Writes the last frame for its own slot and closes the file.
   */
  public void finish() throws IOException {
    if (this.last != null) {
      this.fillUntil(this.last.nanos() + this.period);
    }

    this.close();
  }

  /**
   * @return the length of the written video in seconds
   */
  public double seconds() {
    return this.totalFrames / (double) this.frameRate;
  }

  public int parts() {
    return this.part;
  }

  @Override
  public void close() throws IOException {
    this.writer.close();
  }

  private void fillUntil(long nanos) throws IOException {
    // Every slot that starts before the new frame still shows the previous one
    while (this.startNanos + this.slot * this.period < nanos) {
      if (this.writer.size() + this.last.jpeg().length > MjpegAviWriter.MAX_FILE_SIZE) {
        this.nextPart();
      }

      this.writer.writeFrame(this.last.jpeg());
      this.slot++;
      this.totalFrames++;
    }
  }

  private void nextPart() throws IOException {
    this.writer.close();
    this.part++;

    String name = this.basePath.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String partName = name.substring(0, dot) + "_" + this.part + name.substring(dot);
    this.writer = new MjpegAviWriter(
        this.basePath.resolveSibling(partName),
        this.width,
        this.height,
        this.frameRate
    );
  }
}
