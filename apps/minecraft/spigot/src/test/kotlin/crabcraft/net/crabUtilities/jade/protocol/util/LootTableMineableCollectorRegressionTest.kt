package crabcraft.net.crabUtilities.jade.protocol.util

import java.util.function.Predicate

object LootTableMineableCollectorRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val shears = Any()
        val matchesShears = Predicate<Any> { candidate -> candidate === shears }

        check(LootTableMineableCollector.matchesItemPredicate(matchesShears, shears),
            "matching tool predicate was rejected")
        check(!LootTableMineableCollector.matchesItemPredicate(matchesShears, Any()),
            "non-matching tool predicate was accepted")
        check(!LootTableMineableCollector.matchesItemPredicate(Any(), shears),
            "non-predicate input was accepted")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
