package com.magnus.mintmc.core.commands;

import net.labymod.api.client.chat.command.SubCommand;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;

public class RecorderPingSubCommand extends SubCommand {

  protected RecorderPingSubCommand() {
    super("pong");
  }

  @Override
  public boolean execute(String prefix, String[] arguments) {
    this.displayMessage(Component.text("Ping Pong!", NamedTextColor.GRAY));
    return true;
  }
}
