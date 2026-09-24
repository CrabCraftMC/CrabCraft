package crabcraft.net.crabUtilities.velocity.messaging

object PlayerLookupRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val crab = Candidate("Crab Lord")
        check(
            PlayerLookup.uniqueNicknameMatch(listOf(crab), Candidate::nickname, "crab lord").orElseThrow() === crab,
            "unique nickname did not resolve case-insensitively",
        )
        check(
            PlayerLookup.uniqueNicknameMatch(listOf(crab, Candidate("CRAB LORD")), Candidate::nickname, "Crab Lord")
                .isEmpty,
            "ambiguous nickname resolved to an arbitrary player",
        )
        check("crab" == PlayerLookup.suggestionPrefix("\"crab"), "quoted suggestion prefix was not normalized")
    }

    private data class Candidate(private val value: String) {
        fun nickname() = value
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
