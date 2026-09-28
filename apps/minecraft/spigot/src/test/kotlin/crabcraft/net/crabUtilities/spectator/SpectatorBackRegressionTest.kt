package crabcraft.net.crabUtilities.spectator

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerQuitEvent

object SpectatorBackRegressionTest {
    private val testCommand =
        object : Command("specback") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>) = false
        }

    @JvmStatic
    fun main(args: Array<String>) {
        returnsToAnExactSnapshotOfTheStartingLocation()
        requiresSpectatorMode()
        forgetsTheLocationOnDisconnect()
    }

    private fun returnsToAnExactSnapshotOfTheStartingLocation() {
        val world = proxy(World::class.java) { method, _ -> defaultValue(method.returnType) }
        val original = Location(world, 12.25, 64.0, -8.75, 135f, -20f)
        val stub = PlayerStub(original)
        val command = SpectatorBackCommand()
        command.onGameModeChange(gameModeChange(stub.player))
        original.x = 999.0
        stub.gameMode = GameMode.SPECTATOR
        stub.location = Location(world, 200.0, 100.0, 200.0)
        command.onCommand(stub.player, testCommand, "specback", emptyArray())
        val destination = stub.teleportedTo
        check(destination != null, "the player was not teleported")
        check(destination!!.world === world, "the starting world was not preserved")
        check(
            destination.x == 12.25 && destination.y == 64.0 && destination.z == -8.75,
            "the starting coordinates were not preserved",
        )
        check(destination.yaw == 135f && destination.pitch == -20f, "the starting rotation was not preserved")
        check(stub.gameMode == GameMode.SPECTATOR, "the command changed the player's game mode")
    }

    private fun requiresSpectatorMode() {
        val world = proxy(World::class.java) { method, _ -> defaultValue(method.returnType) }
        val stub = PlayerStub(Location(world, 1.0, 2.0, 3.0))
        val command = SpectatorBackCommand()
        command.onGameModeChange(gameModeChange(stub.player))
        command.onCommand(stub.player, testCommand, "specback", emptyArray())
        check(stub.teleportedTo == null, "a non-spectator was teleported")
    }

    private fun forgetsTheLocationOnDisconnect() {
        val world = proxy(World::class.java) { method, _ -> defaultValue(method.returnType) }
        val stub = PlayerStub(Location(world, 1.0, 2.0, 3.0))
        val command = SpectatorBackCommand()
        command.onGameModeChange(gameModeChange(stub.player))
        command.onPlayerQuit(PlayerQuitEvent(stub.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
        stub.gameMode = GameMode.SPECTATOR
        command.onCommand(stub.player, testCommand, "specback", emptyArray())
        check(stub.teleportedTo == null, "a disconnected player's location was retained")
    }

    private fun gameModeChange(player: Player) =
        PlayerGameModeChangeEvent(
            player,
            GameMode.SPECTATOR,
            PlayerGameModeChangeEvent.Cause.COMMAND,
            Component.empty(),
        )

    private class PlayerStub(var location: Location) {
        val uniqueId = UUID.randomUUID()
        var gameMode = GameMode.SURVIVAL
        var teleportedTo: Location? = null
        val player =
            proxy(Player::class.java) { method, args ->
                when (method.name) {
                    "getUniqueId" -> uniqueId
                    "getGameMode" -> gameMode
                    "getLocation" -> location
                    "isOnline" -> true
                    "teleportAsync" -> {
                        teleportedTo = (args!![0] as Location).clone()
                        CompletableFuture.completedFuture(true)
                    }
                    else -> defaultValue(method.returnType)
                }
            }
    }

    private fun <T> proxy(type: Class<T>, invocation: (Method, Array<out Any?>?) -> Any?): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invocation(method, args) }
        )

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Void.TYPE -> null
            Boolean::class.javaPrimitiveType -> false
            Char::class.javaPrimitiveType -> '\u0000'
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> if (type.isPrimitive) throw AssertionError("Unsupported primitive: $type") else null
        }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
