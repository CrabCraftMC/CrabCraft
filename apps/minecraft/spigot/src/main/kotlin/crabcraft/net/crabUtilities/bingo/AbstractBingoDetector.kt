package crabcraft.net.crabUtilities.bingo

import java.util.UUID

/** Invalidates delayed task completions when a player or card is reset. */
abstract class AbstractBingoDetector : BingoDetector {
    private val playerGenerations = HashMap<UUID, Long>()
    private var generation = 0L

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
    }

    override fun clear() {
        generation++
        playerGenerations.clear()
    }

    protected fun detectorGeneration(): Long = generation

    protected fun attemptToken(playerId: UUID) = AttemptToken(generation, playerGenerations.getOrDefault(playerId, 0L))

    protected fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration() == generation &&
            token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

    protected data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration

        fun playerGeneration(): Long = playerGeneration
    }
}
