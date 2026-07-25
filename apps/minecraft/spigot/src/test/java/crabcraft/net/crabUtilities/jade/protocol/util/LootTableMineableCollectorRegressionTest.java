package crabcraft.net.crabUtilities.jade.protocol.util;

import java.util.function.Predicate;

public final class LootTableMineableCollectorRegressionTest {

    private LootTableMineableCollectorRegressionTest() {
    }

    public static void main(String[] args) {
        Object shears = new Object();
        Predicate<Object> matchesShears = candidate -> candidate == shears;

        check(LootTableMineableCollector.matchesItemPredicate(matchesShears, shears),
                "matching tool predicate was rejected");
        check(!LootTableMineableCollector.matchesItemPredicate(matchesShears, new Object()),
                "non-matching tool predicate was accepted");
        check(!LootTableMineableCollector.matchesItemPredicate(new Object(), shears),
                "non-predicate input was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
