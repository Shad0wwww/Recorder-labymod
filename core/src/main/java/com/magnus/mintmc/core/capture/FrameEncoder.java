package com.magnus.mintmc.core.capture;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.labymod.api.util.logging.Logging;

/**
 * Scales captured frames down and compresses them to JPEG on a thread of its own, so the render
 * thread only copies pixels. When the encoder falls behind, new frames are dropped instead of
 * queued, and the video repeats the previous frame for them.
 */
public class FrameEncoder {

  private static final Logging LOGGER = Logging.create(FrameEncoder.class);

  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
      1, 1,
      30, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(2),
      runnable -> {
        Thread thread = new Thread(runnable, "Recorder Encoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
      },
      new ThreadPoolExecutor.AbortPolicy()
  );

  // Only touched by the encoder thread
  private final ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
  private ImageWriter writer;
  private ImageWriteParam param;
  private BufferedImage image;
  private int[] imagePixels;
  private int[] columnStart = new int[0];
  private int[] rowStart = new int[0];
  private int boxSourceWidth;
  private int boxSourceHeight;

  private volatile int droppedFrames;

  /**
   * Queues a frame for encoding.
   *
   * @param pixels   argb pixels of the source frame
   * @param bottomUp whether the rows start at the bottom, as OpenGL reads them
   * @param release  called once the pixels are no longer needed, on any thread
   * @return false when the frame was dropped because the encoder is busy
   */
  public boolean submit(
      int[] pixels, int sourceWidth, int sourceHeight, boolean bottomUp, long nanos,
      int width, int height, int quality,
      Consumer<EncodedFrame> sink, Runnable release
  ) {
    try {
      this.executor.execute(() -> {
        try {
          byte[] jpeg = this.encode(
              pixels, sourceWidth, sourceHeight, bottomUp, width, height, quality
          );
          release.run();
          sink.accept(new EncodedFrame(jpeg, nanos, width, height));
        } catch (Throwable throwable) {
          release.run();
          LOGGER.error("Could not encode a frame", throwable);
        }
      });
      return true;
    } catch (RejectedExecutionException exception) {
      this.droppedFrames++;
      release.run();
      return false;
    }
  }

  public int droppedFrames() {
    return this.droppedFrames;
  }

  public void shutdown() {
    this.executor.shutdownNow();
  }

  private byte[] encode(
      int[] pixels, int sourceWidth, int sourceHeight, boolean bottomUp,
      int width, int height, int quality
  ) throws IOException {
    if (this.image == null || this.image.getWidth() != width || this.image.getHeight() != height) {
      this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      this.imagePixels = ((DataBufferInt) this.image.getRaster().getDataBuffer()).getData();
    }

    this.scale(pixels, sourceWidth, sourceHeight, bottomUp, width, height);

    if (this.writer == null) {
      Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
      if (!writers.hasNext()) {
        throw new IOException("No JPEG encoder available");
      }

      this.writer = writers.next();
      this.param = this.writer.getDefaultWriteParam();
      this.param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    }

    this.param.setCompressionQuality(Math.max(0.05F, Math.min(1.0F, quality / 100.0F)));
    this.output.reset();
    try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(this.output)) {
      this.writer.setOutput(stream);
      this.writer.write(null, new IIOImage(this.image, null, null), this.param);
    }

    return this.output.toByteArray();
  }

  /**
   * Box filter: every target pixel is the average of the source pixels it covers, which keeps
   * thin lines and text readable when scaling down. Also flips OpenGL frames upright.
   */
  private void scale(
      int[] source, int sourceWidth, int sourceHeight, boolean bottomUp,
      int width, int height
  ) {
    int[] target = this.imagePixels;
    if (sourceWidth == width && sourceHeight == height) {
      for (int y = 0; y < height; y++) {
        int sourceRow = bottomUp ? height - 1 - y : y;
        System.arraycopy(source, sourceRow * width, target, y * width, width);
      }
      return;
    }

    this.updateBoxes(sourceWidth, sourceHeight, width, height);
    int[] columns = this.columnStart;
    int[] rows = this.rowStart;
    for (int y = 0; y < height; y++) {
      int rowFrom = rows[y];
      int rowTo = Math.max(rows[y + 1], rowFrom + 1);
      for (int x = 0; x < width; x++) {
        int columnFrom = columns[x];
        int columnTo = Math.max(columns[x + 1], columnFrom + 1);
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int row = rowFrom; row < rowTo; row++) {
          int sourceRow = bottomUp ? sourceHeight - 1 - row : row;
          int offset = sourceRow * sourceWidth;
          for (int column = columnFrom; column < columnTo; column++) {
            int pixel = source[offset + column];
            red += (pixel >> 16) & 0xFF;
            green += (pixel >> 8) & 0xFF;
            blue += pixel & 0xFF;
          }
        }

        int count = (rowTo - rowFrom) * (columnTo - columnFrom);
        target[y * width + x] = (red / count) << 16 | (green / count) << 8 | (blue / count);
      }
    }
  }

  private void updateBoxes(int sourceWidth, int sourceHeight, int width, int height) {
    if (this.columnStart.length == width + 1
        && this.rowStart.length == height + 1
        && this.boxSourceWidth == sourceWidth
        && this.boxSourceHeight == sourceHeight) {
      return;
    }

    this.boxSourceWidth = sourceWidth;
    this.boxSourceHeight = sourceHeight;
    this.columnStart = new int[width + 1];
    this.rowStart = new int[height + 1];
    for (int x = 0; x <= width; x++) {
      this.columnStart[x] = Math.min((int) ((long) x * sourceWidth / width), sourceWidth - 1);
    }
    this.columnStart[width] = sourceWidth;
    for (int y = 0; y <= height; y++) {
      this.rowStart[y] = Math.min((int) ((long) y * sourceHeight / height), sourceHeight - 1);
    }
    this.rowStart[height] = sourceHeight;
  }
}
