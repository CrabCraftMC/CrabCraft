package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.util.List;

final class PlayerLookupRegressionTest {

    public static void main(String[] args) throws Exception {
        Candidate crab = new Candidate("Crab Lord");
        check(PlayerLookup.uniqueNicknameMatch(List.of(crab), Candidate::nickname, "crab lord")
                        .orElseThrow() == crab,
                "unique nickname did not resolve case-insensitively");

        check(PlayerLookup.uniqueNicknameMatch(
                        List.of(crab, new Candidate("CRAB LORD")), Candidate::nickname, "Crab Lord")
                        .isEmpty(),
                "ambiguous nickname resolved to an arbitrary player");

        String parsed = StringArgumentType.string().parse(new StringReader("\"Crab Lord\""));
        check("Crab Lord".equals(parsed), "quoted nickname with spaces did not parse");
        check("\"Crab Lord\"".equals(StringArgumentType.escapeIfRequired("Crab Lord")),
                "space-containing nickname suggestion was not quoted");
        check("crab".equals(PlayerLookup.suggestionPrefix("\"crab")),
                "quoted suggestion prefix was not normalized");
    }

    private record Candidate(String nickname) {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
