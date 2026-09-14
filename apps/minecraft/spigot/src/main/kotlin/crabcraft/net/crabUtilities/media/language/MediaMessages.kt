package crabcraft.net.crabUtilities.media.language

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

/** Renders Crab Utilities' built-in media messages. */
class MediaMessages {
  fun component(key: String, vararg replacements: Any?): Component =
    plain(MINI_MESSAGE.deserialize(replaceNumberedMarkers(template(key), *replacements)))

  fun component(key: String, replacement: Component): Component = literalReplacement(template(key), replacement)

  fun prefixedComponent(key: String, vararg replacements: Any?): Component {
    val rendered = template("prefix") + replaceNumberedMarkers(template(key), *replacements)
    return plain(MINI_MESSAGE.deserialize(rendered))
  }

  fun string(key: String, vararg replacements: Any?): String = replaceNumberedMarkers(template(key), *replacements)

  companion object {
    private val MINI_MESSAGE = MiniMessage.miniMessage()
    private val TEMPLATES = mapOf(
    "prefix" to "",
    "error.command.cant-perform" to "<#f77069>You can't run that command.",
    "error.command.no-permission" to "<#f77069>You don't have permission to do that.",
    "error.command.disc-name-empty" to "<#f77069>Please give the disc a name.",
    "error.command.horn-name-empty" to "<#f77069>Please give the horn a name.",
    "error.command.url-empty" to "<#f77069>Please paste a link first.",
    "error.command.invalid-settings" to "<#f77069>The volume or range is outside the allowed limits.",
    "error.play.no-matches" to "<#f77069>Couldn't find that track.",
    "error.play.audio-load" to "<#f77069>Something went wrong loading that track.",
    "error.play.busy" to "<#f77069>Too many media tracks are playing. Try again shortly.",
    "error.play.while-playing" to "<#f77069>Something went wrong playing that track.",
    "command.create.syntax" to "<#f77069>/cd create",
    "command.create.description" to "<#F4F1EA>Create a playable disc.",
    "command.create.dialog.title" to "<#FCD05C>Create a Music Disc",
    "command.create.dialog.body" to "<#b0b0b0>Paste a link to YouTube, SoundCloud, Twitch, or a direct audio file.",
    "command.create.dialog.input.url" to "<#FCD05C>Link",
    "command.create.dialog.input.name" to "<#FCD05C>Disc name",
    "command.create.dialog.input.volume" to "<#FCD05C>Volume",
    "command.create.dialog.input.distance" to "<#FCD05C>Range",
    "command.create.dialog.button.create" to "<#77dd77>Create",
    "command.create.dialog.button.cancel" to "<#f77069>Cancel",
    "command.create.messages.error.not-holding-disc" to "<#f77069>Hold a music disc in your hand first.",
    "command.create.messages.created" to "<#77dd77>Your media disc is ready.",
    "command.create.messages.name" to "<#FC835C>Name: <#FCD05C>{0}",
    "command.create.messages.source" to "<#FC835C>Source: <#FCD05C>{0}",
    "command.create.messages.volume" to "<#FC835C>Volume: <#FCD05C>{0}%",
    "command.create.messages.distance" to "<#FC835C>Range: <#FCD05C>{0} blocks",
    "command.edit.syntax" to "<#f77069>/cd edit",
    "command.edit.description" to "<#F4F1EA>Edit the disc you're holding.",
    "command.edit.dialog.title" to "<#FCD05C>Edit a Music Disc",
    "command.edit.messages.error.not-media-disc" to "<#f77069>Hold a media disc to edit it.",
    "command.clear.syntax" to "<#f77069>/disc clear",
    "command.clear.description" to "<#F4F1EA>Restore the disc you're holding.",
    "command.clear.messages.error.not-media-disc" to "<#f77069>Hold a media disc to clear it.",
    "command.clear.messages.cleared" to "<#77dd77>Your music disc has been restored.",
    "command.horn.create.syntax" to "<#f77069>/cd horn create",
    "command.horn.create.description" to "<#F4F1EA>Create a playable horn.",
    "command.horn.create.dialog.title" to "<#FCD05C>Create a Goat Horn",
    "command.horn.create.dialog.body" to "<#b0b0b0>Paste a link to YouTube, SoundCloud, Twitch, or a direct audio file. "
        + "Sounds are capped to the 7s goat horn cooldown.",
    "command.horn.create.dialog.input.url" to "<#FCD05C>Link",
    "command.horn.create.dialog.input.name" to "<#FCD05C>Horn name",
    "command.horn.create.dialog.input.volume" to "<#FCD05C>Volume",
    "command.horn.create.dialog.button.create" to "<#77dd77>Create",
    "command.horn.create.dialog.button.cancel" to "<#f77069>Cancel",
    "command.horn.create.messages.error.not-holding-horn" to "<#f77069>Hold a goat horn in your hand first.",
    "command.horn.create.messages.created" to "<#77dd77>Your media horn is ready.",
    "command.horn.create.messages.name" to "<#FC835C>Name: <#FCD05C>{0}",
    "command.horn.create.messages.source" to "<#FC835C>Source: <#FCD05C>{0}",
    "command.horn.create.messages.volume" to "<#FC835C>Volume: <#FCD05C>{0}%",
    "command.horn.create.messages.length-warning" to "<#FCD05C>Heads up: this track is longer than {0}s, so it will be cut off when blown.",
    "command.horn.edit.syntax" to "<#f77069>/cd horn edit",
    "command.horn.edit.description" to "<#F4F1EA>Edit the horn you're holding.",
    "command.horn.edit.dialog.title" to "<#FCD05C>Edit a Goat Horn",
    "command.horn.edit.messages.error.not-media-horn" to "<#f77069>Hold a media horn to edit it.",
    "command.horn.clear.syntax" to "<#f77069>/horn clear",
    "command.horn.clear.description" to "<#F4F1EA>Restore the horn you're holding.",
    "command.horn.clear.messages.error.not-media-horn" to "<#f77069>Hold a media horn to clear it.",
    "command.horn.clear.messages.cleared" to "<#77dd77>Your goat horn has been restored.",
    "command.help.syntax" to "<#f77069>/cd help",
    "command.help.description" to "<#F4F1EA>Show the available commands.",
    "command.help.messages.header" to "<#FC835C>CD commands",
    "command.help.messages.format" to "{0}<#b0b0b0> — {1}",
    "disc-name.youtube" to "<#FF0000>YouTube disc",
    "disc-name.soundcloud" to "<#FF5500>SoundCloud disc",
    "disc-name.http" to "<#eb632d>HTTP disc",
    "now-playing" to "<#FC835C>Now playing: <#FCD05C>{0}"
    )

    private fun template(key: String): String = TEMPLATES.getOrDefault(key, "<red>Missing media message: " + key)

    private fun replaceNumberedMarkers(template: String, vararg replacements: Any?): String {
      var result = template
      for (index in replacements.indices) result = result.replace("{" + index + "}", replacements[index].toString())
      return result
    }

    @JvmStatic fun literalReplacement(template: String, replacement: Component): Component {
      if (!template.contains("{0}")) {
        return plain(MINI_MESSAGE.deserialize(template).append(Component.space()).append(replacement))
      }
      return plain(MINI_MESSAGE.deserialize(
        template.replace("{0}", "<literal-media-title>"),
        Placeholder.component("literal-media-title", replacement)
      ))
    }

    private fun plain(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)
  }
}
