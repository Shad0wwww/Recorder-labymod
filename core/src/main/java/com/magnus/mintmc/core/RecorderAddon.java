package com.magnus.mintmc.core;

import com.magnus.mintmc.core.commands.RecorderCommand;
import com.magnus.mintmc.core.hud.RecorderHudWidget;
import com.magnus.mintmc.core.listener.RecorderListener;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.models.addon.annotation.AddonMain;

@AddonMain
public class RecorderAddon extends LabyAddon<RecorderConfiguration> {

  private static RecorderAddon instance;

  private RecorderService recorder;

  /**
   * @return the addon, or null before it is enabled
   */
  public static RecorderAddon get() {
    return instance;
  }

  @Override
  protected void enable() {
    instance = this;
    this.registerSettingCategory();

    this.recorder = new RecorderService(this);
    this.registerListener(new RecorderListener(this.recorder, this.configuration()));
    this.registerCommand(new RecorderCommand(this.recorder));

    HudWidgetCategory category = new HudWidgetCategory("recorder");
    this.labyAPI().hudWidgetRegistry().categoryRegistry().register(category);
    this.labyAPI().hudWidgetRegistry().register(new RecorderHudWidget(this, category));
  }

  @Override
  protected Class<RecorderConfiguration> configurationClass() {
    return RecorderConfiguration.class;
  }

  public RecorderService recorder() {
    return this.recorder;
  }
}
