package crabcraft.net.crabUtilities.media.command

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player
import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.dialog.CreateHornDialog
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter
import java.util.Locale

/** /horn creates, edits, or clears playable goat horns. */
@Suppress("UnstableApiUsage")
class HornCommand : BasicCommand {
  override fun execute(source: CommandSourceStack, args: Array<String>) {
    val player = source.sender as? Player
    if (player == null) {
      MediaFeature.sendMessage(source.sender,
        MediaFeature.get().getMessages().prefixedComponent("error.command.cant-perform"))
      return
    }
    val subcommand = if (args.isNotEmpty()) args[0].lowercase(Locale.getDefault()) else "create"
    when (subcommand) {
      "edit" -> CreateHornDialog.openForEdit(player)
      "clear" -> PlayableItemWriter.clearHorn(player)
      else -> CreateHornDialog.open(player)
    }
  }

  override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> =
    if (args.size <= 1) listOf("create", "edit", "clear") else emptyList()

  override fun permission(): String = "crabutilities.media.create"
}
