package crabcraft.net.crabUtilities.media.command

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.media.MediaFeature
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.Locale
import org.bukkit.command.CommandSender

/** /cd: help and disc/horn management shortcuts. */
@Suppress("UnstableApiUsage")
class MediaCommand : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val sender = source.sender
        if (args.isEmpty()) {
            sendUsage(sender)
            return
        }
        when (args[0].lowercase(Locale.getDefault())) {
            "create",
            "edit",
            "clear" -> PlayableItemCommand.DISC.execute(sender, args[0].lowercase(Locale.getDefault()))
            "horn" ->
                PlayableItemCommand.HORN.execute(
                    sender,
                    if (args.size > 1) args[1].lowercase(Locale.getDefault()) else "create",
                )
            "help" -> sendHelp(sender)
            else -> sendUsage(sender)
        }
    }

    private fun sendUsage(sender: CommandSender) =
        MediaFeature.sendMessage(sender, CrabMessages.error("Usage: /cd <create|edit|clear|horn>"))

    private fun sendHelp(sender: CommandSender) {
        val lang = MediaFeature.get().getMessages()
        MediaFeature.sendMessage(sender, lang.component("command.help.messages.header"))
        if (!sender.hasPermission("crabutilities.media.create")) return
        for (action in listOf("create", "edit", "clear", "horn.create", "horn.edit", "horn.clear")) {
            MediaFeature.sendMessage(
                sender,
                lang.component(
                    "command.help.messages.format",
                    lang.string("command.$action.syntax"),
                    lang.string("command.$action.description"),
                ),
            )
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> {
        val sender = source.sender
        // Second argument of /cd horn <create|edit|clear>.
        if (args.size == 2 && args[0].equals("horn", ignoreCase = true)) {
            if (!sender.hasPermission("crabutilities.media.create")) return emptyList()
            return PlayableItemCommand.ACTIONS.filter { it.startsWith(args[1].lowercase(Locale.getDefault())) }
        }
        if (args.size > 1) return emptyList()
        val out = arrayListOf("help")
        if (sender.hasPermission("crabutilities.media.create")) {
            out.addAll(PlayableItemCommand.ACTIONS)
            out.add("horn")
        }
        val prefix = if (args.size == 1) args[0].lowercase(Locale.getDefault()) else ""
        return out.filter { it.startsWith(prefix) }
    }
}
