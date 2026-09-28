package crabcraft.net.crabUtilities.media.command

import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.dialog.CreateMediaDialog
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.Locale
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Shared create, edit and clear actions for the disc, horn and /cd commands. */
@Suppress("UnstableApiUsage")
enum class PlayableItemCommand(private val dialog: CreateMediaDialog, private val clear: (Player) -> Unit) :
    BasicCommand {
    DISC(CreateMediaDialog.DISC, PlayableItemWriter::clearDisc),
    HORN(CreateMediaDialog.HORN, PlayableItemWriter::clearHorn);

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        execute(source.sender, if (args.isNotEmpty()) args[0].lowercase(Locale.getDefault()) else "create")
    }

    fun execute(sender: CommandSender, action: String) {
        if (sender !is Player) {
            MediaFeature.sendMessage(
                sender,
                MediaFeature.get().getMessages().prefixedComponent("error.command.cant-perform"),
            )
            return
        }
        if (action == "clear") clear(sender) else dialog.open(sender, action == "edit")
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> =
        if (args.size <= 1) ACTIONS else emptyList()

    override fun permission() = "crabutilities.media.create"

    companion object {
        @JvmField val ACTIONS = listOf("create", "edit", "clear")
    }
}
