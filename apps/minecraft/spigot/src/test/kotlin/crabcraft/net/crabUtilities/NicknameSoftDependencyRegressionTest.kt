package crabcraft.net.crabUtilities

import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import java.util.UUID

object NicknameSoftDependencyRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, _, _ ->
            throw AssertionError("player should not be queried when EssentialsX is absent")
        } as Player

        check(NicknameComponentResolver.forPlayer(null, player) == null,
            "missing EssentialsX did not fall back for an online player")
        check(NicknameComponentResolver.forUniqueId(null, UUID.randomUUID()) == null,
            "missing EssentialsX did not fall back for a UUID lookup")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
