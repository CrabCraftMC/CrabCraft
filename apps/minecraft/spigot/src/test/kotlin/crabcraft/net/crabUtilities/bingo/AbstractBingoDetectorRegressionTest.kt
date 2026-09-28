package crabcraft.net.crabUtilities.bingo

import java.util.UUID

/** Guards delayed completions against player resets and card changes. */
object AbstractBingoDetectorRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        object : AbstractBingoDetector() {
                fun verifyLifecycle() {
                    val player = UUID.randomUUID()
                    val otherPlayer = UUID.randomUUID()
                    val initial = attemptToken(player)
                    val other = attemptToken(otherPlayer)
                    check(isCurrent(player, initial), "A new attempt must be current")
                    resetPlayer(player)
                    check(!isCurrent(player, initial), "Reset must invalidate that player's attempt")
                    check(isCurrent(otherPlayer, other), "Reset must preserve other players' attempts")
                    val afterReset = attemptToken(player)
                    check(isCurrent(player, afterReset), "A new attempt after reset must be current")
                    clear()
                    check(!isCurrent(player, initial), "Clearing generations must not revive old attempts")
                    check(!isCurrent(player, afterReset), "Clear must invalidate attempts after reset")
                    check(!isCurrent(otherPlayer, other), "Clear must invalidate every player's attempt")
                    val afterClear = attemptToken(player)
                    check(isCurrent(player, afterClear), "A new attempt after clear must be current")
                    resetPlayer(player)
                    check(!isCurrent(player, afterClear), "Player resets must still work after clear")
                    check(!isCurrent(player, afterReset), "Matching reset counts must not revive old card attempts")
                }
            }
            .verifyLifecycle()
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
