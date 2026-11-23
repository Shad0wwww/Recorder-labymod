package com.magnus.mintmc.core.capture;

/**
 * The height clips and recordings are scaled down to. The width follows the aspect ratio of the
 * window. A window smaller than the chosen height is never scaled up.
 */
public enum Resolution {
  P480(480),
  P720(720),
  P1080(1080),
  NATIVE(0);

  private final int height;

  Resolution(int height) {
    this.height = height;
  }

  /**
   * @return the target height in pixels, or 0 for the size of the window
   */
  public int height() {
    return this.height;
  }
}
