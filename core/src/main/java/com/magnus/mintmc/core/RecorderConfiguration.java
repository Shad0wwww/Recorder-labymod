package com.magnus.mintmc.core;

import net.labymod.api.addon.AddonConfig;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget.ButtonSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.KeybindWidget.KeyBindSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SliderWidget.SliderSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.dropdown.DropdownWidget.DropdownSetting;
import net.labymod.api.configuration.loader.annotation.ConfigName;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.labymod.api.util.MethodOrder;
import com.magnus.mintmc.core.capture.Resolution;

@ConfigName("settings")
public class RecorderConfiguration extends AddonConfig {

  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SettingSection("clips")
  @SwitchSetting
  private final ConfigProperty<Boolean> replayBuffer = new ConfigProperty<>(true);

  @KeyBindSetting
  private final ConfigProperty<Key> clipKey = new ConfigProperty<>(Key.F8);

  @SliderSetting(min = 5, max = 120, steps = 5)
  private final ConfigProperty<Integer> clipLength = new ConfigProperty<>(30);

  @SettingSection("recording")
  @KeyBindSetting
  private final ConfigProperty<Key> recordKey = new ConfigProperty<>(Key.F9);

  @SettingSection("video")
  @DropdownSetting
  private final ConfigProperty<Resolution> resolution = new ConfigProperty<>(Resolution.P720);

  @SliderSetting(min = 10, max = 60, steps = 5)
  private final ConfigProperty<Integer> frameRate = new ConfigProperty<>(30);

  @SliderSetting(min = 30, max = 100, steps = 5)
  private final ConfigProperty<Integer> quality = new ConfigProperty<>(80);

  @SettingSection("misc")
  @SwitchSetting
  private final ConfigProperty<Boolean> notifications = new ConfigProperty<>(true);

  @MethodOrder(after = "notifications")
  @ButtonSetting
  public void openFolder() {
    RecorderAddon addon = RecorderAddon.get();
    if (addon != null) {
      addon.recorder().openFolder();
    }
  }

  @Override
  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Boolean> replayBuffer() {
    return this.replayBuffer;
  }

  public ConfigProperty<Key> clipKey() {
    return this.clipKey;
  }

  public ConfigProperty<Integer> clipLength() {
    return this.clipLength;
  }

  public ConfigProperty<Key> recordKey() {
    return this.recordKey;
  }

  public ConfigProperty<Resolution> resolution() {
    return this.resolution;
  }

  public ConfigProperty<Integer> frameRate() {
    return this.frameRate;
  }

  public ConfigProperty<Integer> quality() {
    return this.quality;
  }

  public ConfigProperty<Boolean> notifications() {
    return this.notifications;
  }
}
