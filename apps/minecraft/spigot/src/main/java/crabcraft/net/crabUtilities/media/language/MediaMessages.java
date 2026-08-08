package crabcraft.net.crabUtilities.media.language;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Map;

/** Renders Crab Utilities' built-in media messages. */
public final class MediaMessages {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private static final Map<String, String> TEMPLATES = Map.ofEntries(
    Map.entry("prefix", ""),
    Map.entry("error.command.cant-perform", "<#f77069>You can't run that command."),
    Map.entry("error.command.no-permission", "<#f77069>You don't have permission to do that."),
    Map.entry("error.command.disc-name-empty", "<#f77069>Please give the disc a name."),
    Map.entry("error.command.horn-name-empty", "<#f77069>Please give the horn a name."),
    Map.entry("error.command.url-empty", "<#f77069>Please paste a link first."),
    Map.entry("error.command.invalid-settings",
      "<#f77069>The volume or range is outside the allowed limits."),
    Map.entry("error.play.no-matches", "<#f77069>Couldn't find that track."),
    Map.entry("error.play.audio-load", "<#f77069>Something went wrong loading that track."),
    Map.entry("error.play.busy",
      "<#f77069>Too many media tracks are playing. Try again shortly."),
    Map.entry("error.play.while-playing", "<#f77069>Something went wrong playing that track."),
    Map.entry("command.create.syntax", "<#f77069>/cd create"),
    Map.entry("command.create.description", "<#F4F1EA>Create a playable disc."),
    Map.entry("command.create.dialog.title", "<#FCD05C>Create a Music Disc"),
    Map.entry("command.create.dialog.body",
      "<#b0b0b0>Paste a link to YouTube, SoundCloud, Twitch, or a direct audio file."),
    Map.entry("command.create.dialog.input.url", "<#FCD05C>Link"),
    Map.entry("command.create.dialog.input.name", "<#FCD05C>Disc name"),
    Map.entry("command.create.dialog.input.volume", "<#FCD05C>Volume"),
    Map.entry("command.create.dialog.input.distance", "<#FCD05C>Range"),
    Map.entry("command.create.dialog.button.create", "<#77dd77>Create"),
    Map.entry("command.create.dialog.button.cancel", "<#f77069>Cancel"),
    Map.entry("command.create.messages.error.not-holding-disc",
      "<#f77069>Hold a music disc in your hand first."),
    Map.entry("command.create.messages.created", "<#77dd77>Your media disc is ready."),
    Map.entry("command.create.messages.name", "<#FC835C>Name: <#FCD05C>{0}"),
    Map.entry("command.create.messages.source", "<#FC835C>Source: <#FCD05C>{0}"),
    Map.entry("command.create.messages.volume", "<#FC835C>Volume: <#FCD05C>{0}%"),
    Map.entry("command.create.messages.distance", "<#FC835C>Range: <#FCD05C>{0} blocks"),
    Map.entry("command.edit.syntax", "<#f77069>/cd edit"),
    Map.entry("command.edit.description", "<#F4F1EA>Edit the disc you're holding."),
    Map.entry("command.edit.dialog.title", "<#FCD05C>Edit a Music Disc"),
    Map.entry("command.edit.messages.error.not-media-disc",
      "<#f77069>Hold a media disc to edit it."),
    Map.entry("command.clear.syntax", "<#f77069>/disc clear"),
    Map.entry("command.clear.description", "<#F4F1EA>Restore the disc you're holding."),
    Map.entry("command.clear.messages.error.not-media-disc",
      "<#f77069>Hold a media disc to clear it."),
    Map.entry("command.clear.messages.cleared", "<#77dd77>Your music disc has been restored."),
    Map.entry("command.horn.create.syntax", "<#f77069>/cd horn create"),
    Map.entry("command.horn.create.description", "<#F4F1EA>Create a playable horn."),
    Map.entry("command.horn.create.dialog.title", "<#FCD05C>Create a Goat Horn"),
    Map.entry("command.horn.create.dialog.body",
      "<#b0b0b0>Paste a link to YouTube, SoundCloud, Twitch, or a direct audio file. "
        + "Sounds are capped to the 7s goat horn cooldown."),
    Map.entry("command.horn.create.dialog.input.url", "<#FCD05C>Link"),
    Map.entry("command.horn.create.dialog.input.name", "<#FCD05C>Horn name"),
    Map.entry("command.horn.create.dialog.input.volume", "<#FCD05C>Volume"),
    Map.entry("command.horn.create.dialog.button.create", "<#77dd77>Create"),
    Map.entry("command.horn.create.dialog.button.cancel", "<#f77069>Cancel"),
    Map.entry("command.horn.create.messages.error.not-holding-horn",
      "<#f77069>Hold a goat horn in your hand first."),
    Map.entry("command.horn.create.messages.created", "<#77dd77>Your media horn is ready."),
    Map.entry("command.horn.create.messages.name", "<#FC835C>Name: <#FCD05C>{0}"),
    Map.entry("command.horn.create.messages.source", "<#FC835C>Source: <#FCD05C>{0}"),
    Map.entry("command.horn.create.messages.volume", "<#FC835C>Volume: <#FCD05C>{0}%"),
    Map.entry("command.horn.create.messages.length-warning",
      "<#FCD05C>Heads up: this track is longer than {0}s, so it will be cut off when blown."),
    Map.entry("command.horn.edit.syntax", "<#f77069>/cd horn edit"),
    Map.entry("command.horn.edit.description", "<#F4F1EA>Edit the horn you're holding."),
    Map.entry("command.horn.edit.dialog.title", "<#FCD05C>Edit a Goat Horn"),
    Map.entry("command.horn.edit.messages.error.not-media-horn",
      "<#f77069>Hold a media horn to edit it."),
    Map.entry("command.horn.clear.syntax", "<#f77069>/horn clear"),
    Map.entry("command.horn.clear.description", "<#F4F1EA>Restore the horn you're holding."),
    Map.entry("command.horn.clear.messages.error.not-media-horn",
      "<#f77069>Hold a media horn to clear it."),
    Map.entry("command.horn.clear.messages.cleared", "<#77dd77>Your goat horn has been restored."),
    Map.entry("command.help.syntax", "<#f77069>/cd help"),
    Map.entry("command.help.description", "<#F4F1EA>Show the available commands."),
    Map.entry("command.help.messages.header", "<#FC835C>CD commands"),
    Map.entry("command.help.messages.format", "{0}<#b0b0b0> — {1}"),
    Map.entry("disc-name.youtube", "<#FF0000>YouTube disc"),
    Map.entry("disc-name.soundcloud", "<#FF5500>SoundCloud disc"),
    Map.entry("disc-name.http", "<#eb632d>HTTP disc"),
    Map.entry("now-playing", "<#FC835C>Now playing: <#FCD05C>{0}")
  );

  public Component component(String key, Object... replacements) {
    return plain(MINI_MESSAGE.deserialize(
      replaceNumberedMarkers(template(key), replacements)
    ));
  }

  public Component component(String key, Component replacement) {
    return literalReplacement(template(key), replacement);
  }

  public Component prefixedComponent(String key, Object... replacements) {
    String rendered = template("prefix") + replaceNumberedMarkers(template(key), replacements);
    return plain(MINI_MESSAGE.deserialize(rendered));
  }

  public String string(String key, Object... replacements) {
    return replaceNumberedMarkers(template(key), replacements);
  }

  private static String template(String key) {
    return TEMPLATES.getOrDefault(key, "<red>Missing media message: " + key);
  }

  private static String replaceNumberedMarkers(String template, Object... replacements) {
    String result = template;
    for (int index = 0; index < replacements.length; index++) {
      result = result.replace("{" + index + "}", String.valueOf(replacements[index]));
    }
    return result;
  }

  static Component literalReplacement(String template, Component replacement) {
    if (!template.contains("{0}")) {
      return plain(MINI_MESSAGE.deserialize(template)
        .append(Component.space())
        .append(replacement));
    }
    return plain(MINI_MESSAGE.deserialize(
      template.replace("{0}", "<literal-media-title>"),
      Placeholder.component("literal-media-title", replacement)
    ));
  }

  private static Component plain(Component component) {
    return component.decoration(TextDecoration.ITALIC, false);
  }
}
