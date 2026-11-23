package com.magnus.mintmc.core.listener;

import com.magnus.mintmc.core.RecorderConfiguration;
import com.magnus.mintmc.core.RecorderService;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.input.KeyEvent;
import net.labymod.api.event.client.input.KeyEvent.State;
import net.labymod.api.event.client.lifecycle.GameFpsLimitEvent;
import net.labymod.api.event.client.lifecycle.ShutdownEvent;

public class RecorderListener {

  private final RecorderService recorder;
  private final RecorderConfiguration config;

  public RecorderListener(RecorderService recorder, RecorderConfiguration config) {
    this.recorder = recorder;
    this.config = config;
  }

  /**
   * Fired once per frame by the frame limiter, after the frame is finished and before the next
   * one clears the main target. Only then does the main target hold the whole frame with the gui
   * on every version; during the render event it is already cleared or still empty on some.
   */
  @Subscribe
  public void onFrameEnd(GameFpsLimitEvent event) {
    this.recorder.onFrame();
  }

  @Subscribe
  public void onKey(KeyEvent event) {
    if (event.state() != State.PRESS || !this.config.enabled().get()) {
      return;
    }

    Key key = event.key();
    if (matches(key, this.config.clipKey().get())) {
      this.recorder.saveClip();
    } else if (matches(key, this.config.recordKey().get())) {
      this.recorder.toggleRecording();
    }
  }

  @Subscribe
  public void onShutdown(ShutdownEvent event) {
    this.recorder.shutdown();
  }

  private static boolean matches(Key pressed, Key bound) {
    return bound != null && bound != Key.NONE && pressed == bound;
  }
}
