package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer

/** Opens the settings dialog or configures one preference directly from chat. */
open class SettingsCommand(private val settingsService: PlayerSettingsService, private val dialog: SettingsDialog) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("Only players can use /settings."))
            return true
        }
        // Wait for the stored record to resolve before showing or saving any defaults.
        val uuid = sender.getUniqueId()
        if (!settingsService.isLoaded(uuid)) {
            sender.sendMessage(CrabMessages.muted("Your settings are still loading, try again in a moment."))
            return true
        }
        if (args.isEmpty()) {
            dialog.open(sender)
            return true
        }
        when (args[0].lowercase(Locale.ROOT)) {
            "phantoms" -> handlePhantoms(sender, uuid, args)
            "mentions" -> handleToggle(sender, args, "Chat pings", settingsService.isMentionPingsEnabled(uuid)) { settingsService.setMentionPings(uuid, it) }
            "messages" -> handleToggle(sender, args, "Private messages", settingsService.isAcceptingMessages(uuid)) { settingsService.setAcceptingMessages(uuid, it) }
            "locatorbar", "locator-bar", "locator" -> handleToggle(sender, args, "Locator bar", settingsService.isLocatorBarEnabled(uuid)) { settingsService.setLocatorBar(uuid, it) }
            "bingo" -> handleToggle(sender, args, "Bingo messages", settingsService.isBingoMessagesEnabled(uuid)) { settingsService.setBingoMessages(uuid, it) }
            "coordinatehud" -> handleToggle(sender, args, "Coordinate hud", settingsService.isCoordinateHudEnabled(uuid)) { settingsService.setCoordinateHud(uuid, it) }
            else -> sender.sendMessage(CrabMessages.error("Usage: /settings [phantoms|mentions|messages|locatorbar|bingo|coordinatehud] ..."))
        }
        return true
    }

    private fun handlePhantoms(player: Player, uuid: UUID, args: Array<String>) {
        if (args.size == 1) {
            player.sendMessage(CrabMessages.label("Phantoms", CrabMessages.mini(settingsService.getPhantomMode(uuid).coloredLabel())))
            return
        }
        val mode = parseMode(args[1])
        if (mode == null) {
            player.sendMessage(CrabMessages.error("Usage: /settings phantoms <on|off|safe>"))
            return
        }
        settingsService.setPhantomMode(uuid, mode)
        player.sendMessage(CrabMessages.success("Settings saved."))
    }

    private fun handleToggle(player: Player, args: Array<String>, label: String, currentValue: Boolean, setter: Consumer<Boolean>) {
        if (args.size == 1) {
            player.sendMessage(CrabMessages.label(label, onOff(currentValue)))
            return
        }
        val value = parseToggle(args[1])
        if (value == null) {
            player.sendMessage(CrabMessages.error("Usage: /settings " + args[0].lowercase(Locale.ROOT) + " <on|off>"))
            return
        }
        setter.accept(value)
        player.sendMessage(CrabMessages.success("Settings saved."))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        if (args.size == 1) return SUBCOMMANDS.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        if (args.size == 2) {
            val values = when (args[0].lowercase(Locale.ROOT)) {
                "phantoms" -> PHANTOM_VALUES
                "mentions", "messages", "locatorbar", "locator", "locator-bar", "bingo", "coordinatehud" -> TOGGLE_VALUES
                else -> emptyList()
            }
            return values.filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
        }
        return emptyList()
    }

    companion object {
        private val SUBCOMMANDS = listOf("phantoms", "mentions", "messages", "locatorbar", "bingo", "coordinatehud")
        private val PHANTOM_VALUES = listOf("on", "off", "safe")
        private val TOGGLE_VALUES = listOf("on", "off")

        private fun parseMode(token: String): PhantomMode? = when (token.lowercase(Locale.ROOT)) {
            "on", "enable", "enabled", "true" -> PhantomMode.ON
            "off", "disable", "disabled", "false" -> PhantomMode.OFF
            "safe", "dontattack", "dont-attack", "noattack", "no-attack" -> PhantomMode.SAFE
            else -> null
        }

        private fun parseToggle(token: String): Boolean? = when (token.lowercase(Locale.ROOT)) {
            "on", "enable", "enabled", "true", "yes" -> true
            "off", "disable", "disabled", "false", "no" -> false
            else -> null
        }

        private fun onOff(value: Boolean): Component = if (value) CrabMessages.success("On") else CrabMessages.error("Off")
    }
}
