package com.magnus.mintmc.core;

import com.magnus.mintmc.core.capture.EncodedFrame;
import com.magnus.mintmc.core.capture.FrameEncoder;
import com.magnus.mintmc.core.capture.FrameGrabber;
import com.magnus.mintmc.core.capture.ReplayBuffer;
import com.magnus.mintmc.core.capture.Resolution;
import com.magnus.mintmc.core.video.ConstantRateWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.notification.Notification;
import net.labymod.api.notification.Notification.NotificationButton;

/**
 * The recorder: keeps the replay buffer filled, saves clips from it and runs full recordings.
 * Frames are captured on the render thread, encoded on the encoder thread and written to disk on
 * the io thread, so the game only pays for copying the pixels.
 */
public class RecorderService {

  /** Next to the screenshots folder in the game directory. */
  public static final Path OUTPUT_DIRECTORY = Paths.get("recordings");

  private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern(
      "yyyy-MM-dd_HH-mm-ss",
      Locale.ROOT
  );

  private final RecorderAddon addon;
  private final RecorderConfiguration config;
  private final FrameEncoder encoder = new FrameEncoder();
  private final FrameGrabber grabber = new FrameGrabber(this.encoder);
  private final ReplayBuffer buffer = new ReplayBuffer();
  private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "Recorder IO");
    thread.setDaemon(true);
    return thread;
  });
  private final Consumer<EncodedFrame> sink = this::onFrameEncoded;

  private volatile Recording recording;
  private volatile long keepNanos;
  private long nextCaptureNanos;
  private boolean warnedUnsupported;
  private boolean bufferWasActive;

  public RecorderService(RecorderAddon addon) {
    this.addon = addon;
    this.config = addon.configuration();
  }

  /**
   * Called once per rendered frame, on the render thread.
   */
  public void onFrame() {
    boolean enabled = this.config.enabled().get();
    if (!enabled && this.recording != null) {
      this.stopRecording();
    }

    boolean bufferActive = enabled && this.config.replayBuffer().get();
    if (this.bufferWasActive && !bufferActive) {
      this.buffer.clear();
    }
    this.bufferWasActive = bufferActive;

    Recording recording = this.recording;
    if (!bufferActive && recording == null) {
      return;
    }

    int frameRate = recording != null ? recording.frameRate : this.config.frameRate().get();
    long period = 1_000_000_000L / frameRate;
    long now = System.nanoTime();
    if (now < this.nextCaptureNanos) {
      return;
    }

    // Keep the rhythm, but don't try to catch up after a long frame
    this.nextCaptureNanos = Math.max(this.nextCaptureNanos + period, now);

    if (!this.grabber.isSupported()) {
      if (!this.warnedUnsupported) {
        this.warnedUnsupported = true;
        this.addon.logger().warn("The frame can't be read on this graphics backend");
      }
      return;
    }

    long size = FrameGrabber.targetSize();
    if (size == 0) {
      return;
    }

    int width;
    int height;
    if (recording != null) {
      width = recording.width;
      height = recording.height;
    } else {
      int sourceWidth = (int) (size >>> 32);
      int sourceHeight = (int) size;
      height = outputHeight(sourceHeight, this.config.resolution().get());
      width = evenFloor((int) ((long) sourceWidth * height / sourceHeight));
    }

    this.keepNanos = this.config.clipLength().get() * 1_000_000_000L + period;
    this.grabber.capture(now, width, height, this.config.quality().get(), this.sink);
  }

  /**
   * Saves the last seconds of the replay buffer as a clip.
   */
  public void saveClip() {
    if (!this.config.enabled().get()) {
      return;
    }

    if (!this.config.replayBuffer().get()) {
      this.notify("mintmc.notification.bufferDisabled", NamedTextColor.RED);
      return;
    }

    List<EncodedFrame> frames = this.buffer.snapshot(this.config.clipLength().get() * 1_000_000_000L);
    if (frames.isEmpty()) {
      this.notify("mintmc.notification.bufferEmpty", NamedTextColor.RED);
      return;
    }

    int frameRate = this.config.frameRate().get();
    EncodedFrame newest = frames.get(frames.size() - 1);
    Path path = OUTPUT_DIRECTORY.resolve("clip_" + LocalDateTime.now().format(FILE_DATE) + ".avi");
    this.io.execute(() -> {
      try {
        Files.createDirectories(OUTPUT_DIRECTORY);
        ConstantRateWriter writer = new ConstantRateWriter(
            path, newest.width(), newest.height(), frameRate
        );
        try {
          for (EncodedFrame frame : frames) {
            writer.offer(frame);
          }
        } finally {
          writer.finish();
        }

        this.notifySaved("mintmc.notification.clipSaved", path, writer.seconds());
      } catch (IOException exception) {
        this.addon.logger().error("Could not save the clip", exception);
        this.notify("mintmc.notification.saveFailed", NamedTextColor.RED);
      }
    });
  }

  public void toggleRecording() {
    if (this.recording != null) {
      this.stopRecording();
    } else {
      this.startRecording();
    }
  }

  public boolean isRecording() {
    return this.recording != null;
  }

  /**
   * @return how long the running recording goes, in milliseconds, or 0 without one
   */
  public long recordingMillis() {
    Recording recording = this.recording;
    return recording == null ? 0 : (System.nanoTime() - recording.startNanos) / 1_000_000L;
  }

  /**
   * @return whether the replay buffer is filling, so a clip can be saved
   */
  public boolean isReplayActive() {
    return this.config.enabled().get() && this.config.replayBuffer().get();
  }

  /**
   * @return how many seconds a clip saved now would hold
   */
  public int bufferedSeconds() {
    return Math.min(this.buffer.seconds(), this.config.clipLength().get());
  }

  public void startRecording() {
    if (!this.config.enabled().get() || this.recording != null) {
      return;
    }

    long size = FrameGrabber.targetSize();
    if (size == 0 || !this.grabber.isSupported()) {
      this.notify("mintmc.notification.unsupported", NamedTextColor.RED);
      return;
    }

    int sourceWidth = (int) (size >>> 32);
    int sourceHeight = (int) size;
    int height = outputHeight(sourceHeight, this.config.resolution().get());
    int width = evenFloor((int) ((long) sourceWidth * height / sourceHeight));
    int frameRate = this.config.frameRate().get();
    Path path = OUTPUT_DIRECTORY.resolve(
        "recording_" + LocalDateTime.now().format(FILE_DATE) + ".avi"
    );

    Recording recording = new Recording(path, width, height, frameRate, System.nanoTime());
    this.recording = recording;
    this.io.execute(() -> {
      try {
        Files.createDirectories(OUTPUT_DIRECTORY);
        recording.writer = new ConstantRateWriter(path, width, height, frameRate);
      } catch (IOException exception) {
        this.addon.logger().error("Could not start the recording", exception);
        recording.failed = true;
        if (this.recording == recording) {
          this.recording = null;
        }
        this.notify("mintmc.notification.saveFailed", NamedTextColor.RED);
      }
    });

    this.notify("mintmc.notification.recordingStarted", NamedTextColor.GREEN);
  }

  public void stopRecording() {
    Recording recording = this.recording;
    if (recording == null) {
      return;
    }

    this.recording = null;
    this.io.execute(() -> {
      if (recording.failed || recording.writer == null) {
        return;
      }

      recording.finished = true;
      try {
        recording.writer.finish();
        this.notifySaved("mintmc.notification.recordingSaved", recording.path,
            recording.writer.seconds());
      } catch (IOException exception) {
        this.addon.logger().error("Could not finish the recording", exception);
        this.notify("mintmc.notification.saveFailed", NamedTextColor.RED);
      }
    });
  }

  public void openFolder() {
    try {
      Files.createDirectories(OUTPUT_DIRECTORY);
    } catch (IOException exception) {
      this.addon.logger().error("Could not create the recordings folder", exception);
      return;
    }

    Laby.labyAPI().minecraft().chatExecutor().openFile(OUTPUT_DIRECTORY.toAbsolutePath().toString());
  }

  /**
   * Finishes a running recording so its file stays playable, and stops the threads.
   */
  public void shutdown() {
    this.stopRecording();
    this.io.shutdown();
    try {
      this.io.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }

    this.encoder.shutdown();
  }

  /** Runs on the encoder thread. */
  private void onFrameEncoded(EncodedFrame frame) {
    if (this.config.replayBuffer().get()) {
      this.buffer.add(frame, this.keepNanos);
    }

    Recording recording = this.recording;
    if (recording != null) {
      this.io.execute(() -> {
        if (recording.writer == null || recording.failed || recording.finished) {
          return;
        }

        try {
          recording.writer.offer(frame);
        } catch (IOException exception) {
          recording.failed = true;
          this.addon.logger().error("Could not write the recording", exception);
          this.notify("mintmc.notification.saveFailed", NamedTextColor.RED);
        }
      });
    }
  }

  private void notifySaved(String key, Path path, double seconds) {
    if (!this.config.notifications().get()) {
      return;
    }

    String folder = path.toAbsolutePath().getParent().toString();
    Laby.labyAPI().minecraft().executeOnRenderThread(() -> Notification.builder()
        .title(Component.translatable("mintmc.notification.title"))
        .text(Component.translatable(
            key,
            Component.text(path.getFileName().toString(), NamedTextColor.YELLOW),
            Component.text(String.format(Locale.ROOT, "%.1f", seconds))
        ))
        .addButton(NotificationButton.of(
            Component.translatable("mintmc.notification.openFolder"),
            () -> Laby.labyAPI().minecraft().chatExecutor().openFile(folder)
        ))
        .buildAndPush());
  }

  private void notify(String key, TextColor color) {
    if (!this.config.notifications().get()) {
      return;
    }

    Laby.labyAPI().minecraft().executeOnRenderThread(() -> Notification.builder()
        .title(Component.translatable("mintmc.notification.title"))
        .text(Component.translatable(key).color(color))
        .buildAndPush());
  }

  private static int outputHeight(int sourceHeight, Resolution resolution) {
    int target = resolution.height();
    if (target <= 0 || target >= sourceHeight) {
      return evenFloor(sourceHeight);
    }

    return evenFloor(target);
  }

  /** Most players and converters need an even width and height. */
  private static int evenFloor(int value) {
    return Math.max(2, value & ~1);
  }

  private static final class Recording {

    private final Path path;
    private final int width;
    private final int height;
    private final int frameRate;
    private final long startNanos;

    // Only touched by the io thread
    private ConstantRateWriter writer;
    private boolean finished;
    private volatile boolean failed;

    private Recording(Path path, int width, int height, int frameRate, long startNanos) {
      this.path = path;
      this.width = width;
      this.height = height;
      this.frameRate = frameRate;
      this.startNanos = startNanos;
    }
  }
}
