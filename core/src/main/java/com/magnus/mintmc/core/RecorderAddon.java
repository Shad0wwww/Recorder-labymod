package com.magnus.mintmc.core;

import net.labymod.api.addon.LabyAddon;
import net.labymod.api.models.addon.annotation.AddonMain;
import com.magnus.mintmc.core.commands.RecorderPingCommand;
import com.magnus.mintmc.core.listener.RecorderGameTickListener;

@AddonMain
public class RecorderAddon extends LabyAddon<RecorderConfiguration> {

  @Override
  protected void enable() {
    this.registerSettingCategory();

    this.registerListener(new RecorderGameTickListener(this));
    this.registerCommand(new RecorderPingCommand());

    this.logger().info("Enabled the Addon");
  }

  @Override
  protected Class<RecorderConfiguration> configurationClass() {
    return RecorderConfiguration.class;
  }
}
