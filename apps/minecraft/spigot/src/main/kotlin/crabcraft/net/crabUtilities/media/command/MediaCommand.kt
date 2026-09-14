package crabcraft.net.crabUtilities.media.command

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.dialog.CreateDiscDialog
import crabcraft.net.crabUtilities.media.dialog.CreateHornDialog
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter
import java.util.Locale

/** /cd: help and disc/horn management shortcuts. */
@Suppress("UnstableApiUsage")
class MediaCommand : BasicCommand {
  override fun execute(source: CommandSourceStack, args: Array<String>) {
    val sender = source.sender
    if (args.isEmpty()) {
      sendUsage(sender)
      return
    }
    val plugin = MediaFeature.get()
    val sub = args[0].lowercase(Locale.getDefault())
    when (sub) {
      "create" -> {
        if (sender is Player) CreateDiscDialog.open(sender)
        else MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"))
      }
      "edit" -> {
        if (sender is Player) CreateDiscDialog.openForEdit(sender)
        else MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"))
      }
      "clear" -> {
        if (sender is Player) PlayableItemWriter.clearDisc(sender)
        else MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"))
      }
      "horn" -> {
        if (sender is Player) {
          val hornSub = if (args.size > 1) args[1].lowercase(Locale.getDefault()) else "create"
          when (hornSub) {
            "edit" -> CreateHornDialog.openForEdit(sender)
            "clear" -> PlayableItemWriter.clearHorn(sender)
            else -> CreateHornDialog.open(sender)
          }
        } else MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"))
      }
      "help" -> sendHelp(sender)
      else -> sendUsage(sender)
    }
  }

  private fun sendHelp(sender: CommandSender) {
    val lang = MediaFeature.get().getMessages()
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.header"))
    if (!sender.hasPermission("crabutilities.media.create")) return
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.create.syntax"), lang.string("command.create.description")))
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.edit.syntax"), lang.string("command.edit.description")))
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.clear.syntax"), lang.string("command.clear.description")))
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.horn.create.syntax"), lang.string("command.horn.create.description")))
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.horn.edit.syntax"), lang.string("command.horn.edit.description")))
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.horn.clear.syntax"), lang.string("command.horn.clear.description")))
  }

  override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
    val sender = source.sender
    // Second argument of "/cd horn <create|edit|clear>".
    if (args.size == 2 && args[0].equals("horn", ignoreCase = true)) {
      if (!sender.hasPermission("crabutilities.media.create")) return emptyList()
      return listOf("create", "edit", "clear").filter { it.startsWith(args[1].lowercase(Locale.getDefault())) }
    }
    if (args.size > 1) return emptyList()
    val out = ArrayList<String>()
    out.add("help")
    if (sender.hasPermission("crabutilities.media.create")) out.add("create")
    if (sender.hasPermission("crabutilities.media.create")) out.add("edit")
    if (sender.hasPermission("crabutilities.media.create")) out.add("clear")
    if (sender.hasPermission("crabutilities.media.create")) out.add("horn")
    val prefix = if (args.size == 1) args[0].lowercase(Locale.getDefault()) else ""
    return out.filter { it.startsWith(prefix) }
  }

  companion object {
    private fun sendUsage(sender: CommandSender) {
      MediaFeature.sendMessage(sender, CrabMessages.error("Usage: /cd <create|edit|clear|horn>"))
    }
  }
}
