package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class AwardBiomesRegressionTest {

    public static void main(String[] args) throws Exception {
        JsonObject row = loadSeedRow();
        check(row.get("title").getAsString().equals("Globetrotter"),
                "unexpected biomes award title");
        check(row.getAsJsonObject("reader").get("type").getAsString().equals("set-count"),
                "Globetrotter does not use the set-count reader");

        AwardDefinition definition = definitionFrom(row);
        AwardEvaluator evaluator = new AwardEvaluator(Map.of(definition.id, definition));
        JsonObject advancements = JsonParser.parseString("""
                {
                  "minecraft:adventure/adventuring_time": {
                    "criteria": {
                      "minecraft:desert": "2026-08-01 12:00:00 +0000",
                      "minecraft:forest": "2026-08-02 12:00:00 +0000",
                      "minecraft:plains": "2026-08-03 12:00:00 +0000"
                    },
                    "done": false
                  }
                }
                """).getAsJsonObject();

        Map<String, Double> scores = evaluator.evaluate(new JsonObject(), null, advancements);
        check(scores.get("biomes") == 3d,
                "Globetrotter did not count visited biome criteria");

        JsonObject emptyCriteria = JsonParser.parseString("""
                {"minecraft:adventure/adventuring_time":{"criteria":{},"done":false}}
                """).getAsJsonObject();
        Map<String, Double> zeroScores = evaluator.evaluate(
                new JsonObject(), null, emptyCriteria);
        check(zeroScores.containsKey("biomes") && zeroScores.get("biomes") == 0d,
                "an empty criteria set was treated as missing advancement data");

        Map<String, Double> missingScores = evaluator.evaluate(
                new JsonObject(), null, new JsonObject());
        check(!missingScores.containsKey("biomes"),
                "missing advancement data would overwrite the last valid biome count");
    }

    private static JsonObject loadSeedRow() throws Exception {
        try (var input = AwardBiomesRegressionTest.class
                .getResourceAsStream("/crabcraft/awards.json")) {
            check(input != null, "bundled awards seed is missing");
            JsonArray rows = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : rows) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("id").getAsString().equals("biomes")) return row;
            }
        }
        throw new AssertionError("biomes is missing from the award seed");
    }

    private static AwardDefinition definitionFrom(JsonObject row) {
        JsonObject reader = row.getAsJsonObject("reader");
        AwardDefinition definition = new AwardDefinition();
        definition.id = row.get("id").getAsString();
        definition.reader = new AwardDefinition.Reader();
        definition.reader.type = reader.get("type").getAsString();
        definition.reader.path = reader.getAsJsonArray("path").asList().stream()
                .map(element -> element.getAsString())
                .toList();
        return definition;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
