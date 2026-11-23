package com.magnus.mintmc.core.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import net.labymod.api.Laby;
import net.labymod.api.client.render.capture.ScreenRegionCapture;
import net.labymod.api.laby3d.foreign.target.opengl.GlForeignRenderTargetRegistry;
import net.labymod.api.util.CastUtil;
import net.labymod.api.util.logging.Logging;
import net.labymod.laby3d.api.opengl.GlFunctions;
import net.labymod.laby3d.api.opengl.GlRenderDevice;
import net.labymod.laby3d.api.opengl.GlResource;
import net.labymod.laby3d.api.pipeline.target.RenderTarget;
import net.labymod.laby3d.api.rhi.NativeHandle;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Reads finished frames from the main render target, on the render thread.
 *
 * <p>Where the version provides a {@link ScreenRegionCapture} it is used, which works on any
 * graphics backend. Otherwise frames are read through OpenGL into two pixel buffer objects in
 * turn: a frame is read into one while the other, read a capture earlier, is copied out. By then
 * the GPU has long finished it, so the render thread never waits for the GPU.
 */
public class FrameGrabber {

  private static final Logging LOGGER = Logging.create(FrameGrabber.class);

  private static final int NO_FRAMEBUFFER = -1;
  private static final int READ_FRAMEBUFFER = 0x8CA8;
  private static final int PIXEL_PACK_BUFFER = 0x88EB;
  private static final int STREAM_READ = 0x88E1;
  private static final int BGRA = 0x80E1;
  private static final int UNSIGNED_INT_8_8_8_8_REV = 0x8367;

  /**
   * The pack parameters that decide where glReadPixels writes. Screenshot code leaves them set to
   * the window size, so they are reset to tight packing before every read.
   */
  private static final int[] PACK_PARAMETERS = {
      0x0D02, // GL_PACK_ROW_LENGTH
      0x0D03, // GL_PACK_SKIP_ROWS
      0x0D04, // GL_PACK_SKIP_PIXELS
      0x0D05, // GL_PACK_ALIGNMENT
      0x806C, // GL_PACK_IMAGE_HEIGHT
      0x806B, // GL_PACK_SKIP_IMAGES
      0x0D00, // GL_PACK_SWAP_BYTES
      0x0D01, // GL_PACK_LSB_FIRST
  };
  private static final int[] TIGHT_PACK_VALUES = {0, 0, 0, 1, 0, 0, 0, 0};

  /** Pixel arrays handed to the encoder and given back, so frames don't allocate. */
  private static final int POOL_SIZE = 4;

  private final FrameEncoder encoder;
  private final ArrayDeque<int[]> pool = new ArrayDeque<>();
  private int poolPixels;

  // OpenGL path
  private final int[] pixelBuffers = {0, 0};
  private final long[] pendingNanos = {0, 0};
  private final int[] pendingWidth = {0, 0};
  private final int[] pendingHeight = {0, 0};
  private final boolean[] pending = {false, false};
  private int current;
  private int bufferPixels;
  private ByteBuffer readBuffer;
  private boolean glFailed;

  public FrameGrabber(FrameEncoder encoder) {
    this.encoder = encoder;
  }

  /**
   * @return the size of the main render target, as {@code width << 32 | height}, or 0 when there
   *         is nothing to capture
   */
  public static long targetSize() {
    RenderTarget target = Laby.labyAPI().minecraft().mainTarget();
    if (target == null || target.width() <= 0 || target.height() <= 0) {
      return 0;
    }

    return (long) target.width() << 32 | target.height();
  }

  /**
   * Captures the current frame.
   *
   * @param width   the width of the encoded frame
   * @param height  the height of the encoded frame
   * @param quality the JPEG quality from 1 to 100
   * @param sink    receives the encoded frame, on the encoder thread
   */
  public void capture(long nanos, int width, int height, int quality,
      Consumer<EncodedFrame> sink) {
    RenderTarget target = Laby.labyAPI().minecraft().mainTarget();
    int sourceWidth = target.width();
    int sourceHeight = target.height();

    ScreenRegionCapture capture = Laby.references().getScreenRegionCapture();
    if (capture != null) {
      capture.capture(0, 0, sourceWidth, sourceHeight, argb -> {
        if (argb.length < sourceWidth * sourceHeight) {
          return;
        }

        this.encoder.submit(
            argb, sourceWidth, sourceHeight, false, nanos,
            width, height, quality, sink, () -> { }
        );
      });
      return;
    }

    if (this.glFailed) {
      return;
    }

    int framebuffer = framebufferId(target);
    if (framebuffer == NO_FRAMEBUFFER) {
      return;
    }

    try {
      this.captureGl(framebuffer, sourceWidth, sourceHeight, nanos, width, height, quality, sink);
    } catch (RuntimeException exception) {
      // Don't spam the log every frame on a driver that refuses the read
      this.glFailed = true;
      LOGGER.error("Reading the frame through OpenGL failed, capture is disabled", exception);
    }
  }

  /**
   * Drops the frames that are still being read, for example when the recording size changes.
   */
  public void discardPending() {
    this.pending[0] = false;
    this.pending[1] = false;
  }

  /**
   * Frees the pixel buffers. Render thread only.
   */
  public void release() {
    this.discardPending();
    for (int index = 0; index < this.pixelBuffers.length; index++) {
      if (this.pixelBuffers[index] != 0) {
        GL15.glDeleteBuffers(this.pixelBuffers[index]);
        this.pixelBuffers[index] = 0;
      }
    }

    this.bufferPixels = 0;
    this.readBuffer = null;
    synchronized (this.pool) {
      this.pool.clear();
    }
  }

  public boolean isSupported() {
    return Laby.references().getScreenRegionCapture() != null
        || (!this.glFailed
        && framebufferId(Laby.labyAPI().minecraft().mainTarget()) != NO_FRAMEBUFFER);
  }

  private void captureGl(
      int framebuffer, int sourceWidth, int sourceHeight, long nanos,
      int width, int height, int quality, Consumer<EncodedFrame> sink
  ) {
    GlFunctions functions = CastUtil.requireInstanceOf(
        Laby.references().laby3D().renderDevice(),
        GlRenderDevice.class
    ).functions();
    GlForeignRenderTargetRegistry registry = CastUtil.requireInstanceOf(
        Laby.references().laby3D().foreignRenderTargetRegistry(),
        GlForeignRenderTargetRegistry.class
    );

    int pixels = sourceWidth * sourceHeight;
    if (this.bufferPixels != pixels) {
      this.allocateBuffers(functions, pixels);
    }

    int previousReadFramebuffer = registry.getReadFbo();
    if (previousReadFramebuffer != framebuffer) {
      functions.bindFramebuffer(READ_FRAMEBUFFER, framebuffer);
    }

    int next = this.current ^ 1;
    try {
      for (int index = 0; index < PACK_PARAMETERS.length; index++) {
        functions.pixelStorei(PACK_PARAMETERS[index], TIGHT_PACK_VALUES[index]);
      }

      // Start the read of this frame, it lands in the buffer without waiting for the GPU
      functions.bindBuffer(PIXEL_PACK_BUFFER, this.pixelBuffers[this.current]);
      GL11.glReadPixels(0, 0, sourceWidth, sourceHeight, BGRA, UNSIGNED_INT_8_8_8_8_REV, 0L);
      this.pending[this.current] = true;
      this.pendingNanos[this.current] = nanos;
      this.pendingWidth[this.current] = sourceWidth;
      this.pendingHeight[this.current] = sourceHeight;

      // Copy out the frame read the last time
      if (this.pending[next]) {
        this.pending[next] = false;
        int[] target = this.borrow(pixels);
        if (target != null
            && this.pendingWidth[next] == sourceWidth
            && this.pendingHeight[next] == sourceHeight) {
          functions.bindBuffer(PIXEL_PACK_BUFFER, this.pixelBuffers[next]);
          this.readBuffer.clear();
          GL15.glGetBufferSubData(PIXEL_PACK_BUFFER, 0L, this.readBuffer);
          this.readBuffer.asIntBuffer().get(target, 0, pixels);

          this.encoder.submit(
              target, sourceWidth, sourceHeight, true, this.pendingNanos[next],
              width, height, quality, sink, () -> this.giveBack(target)
          );
        } else if (target != null) {
          this.giveBack(target);
        }
      }
    } finally {
      functions.bindBuffer(PIXEL_PACK_BUFFER, 0);
      if (previousReadFramebuffer != framebuffer) {
        functions.bindFramebuffer(READ_FRAMEBUFFER, previousReadFramebuffer);
      }
    }

    this.current = next;
  }

  private void allocateBuffers(GlFunctions functions, int pixels) {
    this.discardPending();
    for (int index = 0; index < this.pixelBuffers.length; index++) {
      if (this.pixelBuffers[index] == 0) {
        this.pixelBuffers[index] = GL15.glGenBuffers();
      }

      functions.bindBuffer(PIXEL_PACK_BUFFER, this.pixelBuffers[index]);
      GL15.glBufferData(PIXEL_PACK_BUFFER, (long) pixels * 4L, STREAM_READ);
    }

    functions.bindBuffer(PIXEL_PACK_BUFFER, 0);
    this.readBuffer = ByteBuffer.allocateDirect(pixels * 4).order(ByteOrder.nativeOrder());
    this.bufferPixels = pixels;
  }

  /**
   * @return a pixel array of the given size, or null when all are in use by the encoder
   */
  private int[] borrow(int pixels) {
    synchronized (this.pool) {
      if (this.poolPixels != pixels) {
        this.pool.clear();
        this.poolPixels = pixels;
        for (int index = 0; index < POOL_SIZE; index++) {
          this.pool.add(new int[pixels]);
        }
      }

      return this.pool.pollFirst();
    }
  }

  private void giveBack(int[] pixels) {
    synchronized (this.pool) {
      if (pixels.length == this.poolPixels) {
        this.pool.addLast(pixels);
      }
    }
  }

  /**
   * @return the framebuffer of the target, or {@link #NO_FRAMEBUFFER} when it isn't backed by
   *         OpenGL
   */
  private static int framebufferId(RenderTarget target) {
    if (target == null) {
      return NO_FRAMEBUFFER;
    }

    NativeHandle handle = target.nativeHandle().orElse(null);
    if (handle instanceof NativeHandle.Gl gl) {
      return gl.name();
    }

    if (handle != null) {
      return NO_FRAMEBUFFER;
    }

    // Versions from before nativeHandle() existed expose the framebuffer through GlResource
    if (target instanceof GlResource resource) {
      try {
        return resource.getId();
      } catch (RuntimeException exception) {
        return NO_FRAMEBUFFER;
      }
    }

    return NO_FRAMEBUFFER;
  }
}
