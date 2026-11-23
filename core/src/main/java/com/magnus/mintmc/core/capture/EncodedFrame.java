package com.magnus.mintmc.core.capture;

/**
 * One captured frame, compressed to JPEG.
 *
 * @param jpeg   the image, never changed after creation
 * @param nanos  when the frame was captured, {@link System#nanoTime()}
 * @param width  the image width in pixels
 * @param height the image height in pixels
 */
public record EncodedFrame(byte[] jpeg, long nanos, int width, int height) {

  public boolean sameSize(int width, int height) {
    return this.width == width && this.height == height;
  }
}
