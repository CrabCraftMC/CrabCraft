package crabcraft.net.crabUtilities

import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.entity.Player

object NicknameSoftDependencyRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ ->
                throw AssertionError("player should not be queried when EssentialsX is absent")
            } as Player
        check(
            NicknameComponentResolver.forPlayer(null, player) == null,
            "missing EssentialsX did not fall back for an online player",
        )
        check(
            NicknameComponentResolver.forUniqueId(null, UUID.randomUUID()) == null,
            "missing EssentialsX did not fall back for a UUID lookup",
        )
        check(!VanishStatus.isVanished(null, player), "missing EssentialsX did not default to visible")
        check(!VanishStatus.setVanished(null, player, true), "missing EssentialsX claimed to apply vanish state")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
