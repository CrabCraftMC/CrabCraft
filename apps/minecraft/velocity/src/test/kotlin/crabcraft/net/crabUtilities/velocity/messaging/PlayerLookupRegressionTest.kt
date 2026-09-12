package crabcraft.net.crabUtilities.velocity.messaging

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType


object PlayerLookupRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val crab = Candidate("Crab Lord")
        check(PlayerLookup.uniqueNicknameMatch(listOf(crab), Candidate::nickname, "crab lord")
                        .orElseThrow() === crab,
                "unique nickname did not resolve case-insensitively")

        check(PlayerLookup.uniqueNicknameMatch(
                        listOf(crab, Candidate("CRAB LORD")), Candidate::nickname, "Crab Lord")
                        .isEmpty(),
                "ambiguous nickname resolved to an arbitrary player")

        val parsed = StringArgumentType.string().parse(StringReader("\"Crab Lord\""))
        check("Crab Lord".equals(parsed), "quoted nickname with spaces did not parse")
        check("\"Crab Lord\"".equals(StringArgumentType.escapeIfRequired("Crab Lord")),
                "space-containing nickname suggestion was not quoted")
        check("crab".equals(PlayerLookup.suggestionPrefix("\"crab")),
                "quoted suggestion prefix was not normalized")
    }

    private data class Candidate(private val nickname: String) {
        fun nickname(): String = nickname
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
