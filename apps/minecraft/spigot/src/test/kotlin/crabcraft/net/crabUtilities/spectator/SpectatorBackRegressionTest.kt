package crabcraft.net.crabUtilities.spectator

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture

object SpectatorBackRegressionTest {
    private val commandArgument = object : org.bukkit.command.Command("specback") {
        override fun execute(sender: org.bukkit.command.CommandSender, commandLabel: String, args: Array<String>): Boolean = false
    }
    @JvmStatic
    fun main(args: Array<String>) {
        returnsToAnExactSnapshotOfTheStartingLocation()
        requiresSpectatorMode()
        forgetsTheLocationOnDisconnect()
    }
    private fun returnsToAnExactSnapshotOfTheStartingLocation() {
        val world = proxy(World::class.java) { method, _ -> defaultValue(method.returnType) }
        val original = Location(world, 12.25, 64.0, -8.75, 135.0F, -20.0F)
        val stub = PlayerStub(original)
        val command = SpectatorBackCommand()
        command.onGameModeChange(gameModeChange(stub.player))
        original.setX(999.0)
        stub.gameMode = GameMode.SPECTATOR
        stub.location = Location(world, 200.0, 100.0, 200.0)
        command.onCommand(stub.player, commandArgument, "specback", emptyArray())
        val destination = stub.teleportedTo
        check(destination != null, "the player was not teleported")
        check(destination!!.getWorld() === world, "the starting world was not preserved")
        check(destination.getX() == 12.25 && destination.getY() == 64.0 && destination.getZ() == -8.75, "the starting coordinates were not preserved")
        check(destination.getYaw() == 135.0F && destination.getPitch() == -20.0F, "the starting rotation was not preserved")
        check(stub.gameMode == GameMode.SPECTATOR, "the command changed the player's game mode")
    }
    private fun requiresSpectatorMode() {
        val world = proxy(World::class.java) { method, _ -> defaultValue(method.returnType) }
        val stub = PlayerStub(Location(world, 1.0, 2.0, 3.0))
        val command = SpectatorBackCommand()
        command.onGameModeChange(gameModeChange(stub.player))
        command.onCommand(stub.player, commandArgument, "specback", emptyArray())
        check(stub.teleportedTo == null, "a non-spectator was teleported")
    }
    private fun forgetsTheLocationOnDisconnect() {
        val world = proxy(World::class.java) { method, _ -> defaultValue(method.returnType) }
        val stub = PlayerStub(Location(world, 1.0, 2.0, 3.0))
        val command = SpectatorBackCommand()
        command.onGameModeChange(gameModeChange(stub.player))
        command.onPlayerQuit(PlayerQuitEvent(stub.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
        stub.gameMode = GameMode.SPECTATOR
        command.onCommand(stub.player, commandArgument, "specback", emptyArray())
        check(stub.teleportedTo == null, "a disconnected player's location was retained")
    }
    private fun gameModeChange(player: Player): PlayerGameModeChangeEvent =
        PlayerGameModeChangeEvent(player, GameMode.SPECTATOR, PlayerGameModeChangeEvent.Cause.COMMAND, Component.empty())
    private class PlayerStub(var location: Location) {
        private val uniqueId = UUID.randomUUID()
        var gameMode = GameMode.SURVIVAL
        var teleportedTo: Location? = null
        val player: Player = proxy(Player::class.java) { method, args ->
            when (method.name) {
                "getUniqueId" -> uniqueId
                "getGameMode" -> gameMode
                "getLocation" -> location
                "isOnline" -> true
                "teleportAsync" -> { teleportedTo = (args!![0] as Location).clone(); CompletableFuture.completedFuture(true) }
                else -> defaultValue(method.returnType)
            }
        }
    }
    private fun interface Invocation { fun invoke(method: java.lang.reflect.Method, args: Array<out Any?>?): Any? }
    private fun <T> proxy(type: Class<T>, invocation: Invocation): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invocation.invoke(method, args) })
    private fun defaultValue(type: Class<*>): Any? {
        if (type == Void.TYPE || !type.isPrimitive) return null
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0.0F
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> throw AssertionError("Unsupported primitive: $type")
        }
    }

    private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
