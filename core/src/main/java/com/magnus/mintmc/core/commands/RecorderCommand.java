package com.magnus.mintmc.core.commands;

import com.magnus.mintmc.core.RecorderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.labymod.api.client.chat.command.Command;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;

/**
 * {@code /recorder clip|record|folder}, the same actions as the hotkeys.
 */
public class RecorderCommand extends Command {

  private static final String[] ACTIONS = {"clip", "record", "folder"};

  private final RecorderService recorder;

  public RecorderCommand(RecorderService recorder) {
    super("recorder", "rec");
    this.recorder = recorder;
  }

  @Override
  public boolean execute(String prefix, String[] arguments) {
    String action = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "clip" -> {
        this.recorder.saveClip();
        this.displayMessage(Component.translatable("mintmc.command.clip").color(NamedTextColor.GRAY));
      }
      case "record" -> {
        this.recorder.toggleRecording();
        this.displayMessage(Component.translatable(
            this.recorder.isRecording() ? "mintmc.command.recordStarted"
                : "mintmc.command.recordStopped"
        ).color(NamedTextColor.GRAY));
      }
      case "folder" -> {
        this.recorder.openFolder();
        this.displayMessage(Component.translatable("mintmc.command.folder").color(NamedTextColor.GRAY));
      }
      default -> this.displayMessage(
          Component.translatable("mintmc.command.usage").color(NamedTextColor.RED)
      );
    }

    return true;
  }

  @Override
  public List<String> complete(String[] arguments) {
    List<String> result = new ArrayList<>();
    if (arguments.length <= 1) {
      String start = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
      for (String action : ACTIONS) {
        if (action.startsWith(start)) {
          result.add(action);
        }
      }
    }

    return result;
  }
}
