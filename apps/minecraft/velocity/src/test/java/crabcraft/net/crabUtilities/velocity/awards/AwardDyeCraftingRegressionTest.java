package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

final class AwardDyeCraftingRegressionTest {

    public static void main(String[] args) {
        AwardDefinition definition = new AwardDefinition();
        definition.id = "craft_dye";
        definition.reader = new AwardDefinition.Reader();
        definition.reader.type = "match-sum";
        definition.reader.path = List.of("minecraft:crafted");
        definition.reader.patterns = List.of("minecraft:.+_dye");

        double score = new AwardEvaluator(Map.of(definition.id, definition)).evaluate(
                JsonParser.parseString("""
                        {"stats":{"minecraft:crafted":{
                          "minecraft:red_dye":16,
                          "minecraft:blue_dye":24,
                          "minecraft:white_dye":8,
                          "minecraft:red_wool":64,
                          "example:red_dye":100
                        }}}
                        """).getAsJsonObject()).get(definition.id);

        check(score == 48d, "dye output quantities were not summed exactly once");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
