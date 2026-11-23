package com.magnus.mintmc.core.hud;

import com.magnus.mintmc.core.RecorderAddon;
import com.magnus.mintmc.core.RecorderConfiguration;
import com.magnus.mintmc.core.RecorderService;
import java.util.Locale;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidgetConfig;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine.State;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.client.resources.ResourceLocation;
import net.labymod.api.configuration.loader.property.ConfigProperty;

/**
 * Shows whether the recorder is running: a red REC with the elapsed time while recording, and
 * how many seconds a clip would hold while instant replay is on.
 */
public class RecorderHudWidget extends TextHudWidget<RecorderHudWidget.Config> {

  private static final String KEY = "mintmc.hudWidget.recorder.";

  // Built once, the status only switches between these
  private static final Component STATUS_RECORDING = Component.translatable(
      KEY + "recording", NamedTextColor.RED
  );
  private static final Component STATUS_REPLAY = Component.translatable(
      KEY + "replay", NamedTextColor.GREEN
  );
  private static final Component STATUS_IDLE = Component.translatable(
      KEY + "idle", NamedTextColor.GRAY
  );
  private static final Component STATUS_DISABLED = Component.translatable(
      KEY + "disabled", NamedTextColor.DARK_GRAY
  );

  private final RecorderAddon addon;

  private TextLine statusLine;
  private TextLine timeLine;
  private TextLine bufferLine;

  // The last values shown, so components are only rebuilt when the text changes
  private long shownSeconds = -1;
  private Component timeValue;
  private int shownBuffer = -1;
  private int shownLength = -1;
  private Component bufferValue;

  public RecorderHudWidget(RecorderAddon addon, HudWidgetCategory category) {
    super("recorder", Config.class);
    this.addon = addon;
    this.bindCategory(category);
    this.setIcon(Icon.texture(ResourceLocation.create("mintmc", "textures/icon.png")));
  }

  @Override
  public void load(Config config) {
    super.load(config);
    this.statusLine = this.createLine(Component.translatable(KEY + "status"), STATUS_IDLE);
    this.timeLine = this.createLine(Component.translatable(KEY + "time"), "00:00");
    this.bufferLine = this.createLine(Component.translatable(KEY + "buffer"), "0 s");
  }

  @Override
  public void onTick(boolean isEditorContext) {
    RecorderService recorder = this.addon.recorder();
    RecorderConfiguration settings = this.addon.configuration();
    if (recorder == null) {
      return;
    }

    boolean enabled = settings.enabled().get();
    boolean recording = recorder.isRecording();
    boolean replay = recorder.isReplayActive();

    Component status;
    if (!enabled) {
      status = STATUS_DISABLED;
    } else if (recording) {
      status = STATUS_RECORDING;
    } else if (replay) {
      status = STATUS_REPLAY;
    } else {
      status = STATUS_IDLE;
    }
    this.statusLine.updateAndFlush(status);

    // Recording time, the editor shows an example
    boolean showTime = recording || isEditorContext;
    if (showTime) {
      long seconds = recording ? recorder.recordingMillis() / 1000L : 83L;
      if (seconds != this.shownSeconds) {
        this.shownSeconds = seconds;
        this.timeValue = Component.text(
            String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L), NamedTextColor.RED
        );
      }
      this.timeLine.updateAndFlush(this.timeValue);
    }
    this.timeLine.setState(showTime ? State.VISIBLE : State.HIDDEN);

    // Seconds in the replay buffer
    boolean showBuffer = this.getConfig().showBuffer().get()
        && ((enabled && replay) || isEditorContext);
    if (showBuffer) {
      int length = settings.clipLength().get();
      int buffered = enabled && replay ? recorder.bufferedSeconds() : length;
      if (buffered != this.shownBuffer || length != this.shownLength) {
        this.shownBuffer = buffered;
        this.shownLength = length;
        this.bufferValue = Component.text(buffered + " / " + length + " s");
      }
      this.bufferLine.updateAndFlush(this.bufferValue);
    }
    this.bufferLine.setState(showBuffer ? State.VISIBLE : State.HIDDEN);
  }

  public static class Config extends TextHudWidgetConfig {

    @SwitchSetting
    private final ConfigProperty<Boolean> showBuffer = new ConfigProperty<>(true);

    public ConfigProperty<Boolean> showBuffer() {
      return this.showBuffer;
    }
  }
}
