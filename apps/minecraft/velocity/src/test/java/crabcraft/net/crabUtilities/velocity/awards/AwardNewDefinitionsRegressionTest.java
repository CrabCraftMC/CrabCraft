package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class AwardNewDefinitionsRegressionTest {

    public static void main(String[] args) throws Exception {
        Map<String, JsonObject> rows = loadSeedRows();
        checkTitle(rows, "collect_potato", "Absolute Spud");
        checkTitle(rows, "craft_bricks", "Bricklayer");
        checkTitle(rows, "craft_honey_block", "Honey I'm Home");
        checkTitle(rows, "craft_redstone_components", "Redstone Crafter");
        checkTitle(rows, "mine_ground", "Excavator");
        checkTitle(rows, "mine_lectern", "Librarian Reroller");
        checkTitle(rows, "xp_level", "Level Headed");
        check(rows.get("xp_level").getAsJsonObject("reader").get("type").getAsString()
                        .equals("custom-int"),
                "Level Headed does not use the custom reader");

        Map<String, AwardDefinition> definitions = new HashMap<>();
        for (JsonObject row : rows.values()) {
            AwardDefinition definition = definitionFrom(row);
            definitions.put(definition.id, definition);
        }

        JsonObject stats = JsonParser.parseString("""
                {"stats":{
                  "minecraft:picked_up":{"minecraft:potato":23},
                  "minecraft:crafted":{
                    "minecraft:bricks":37,
                    "minecraft:brick":41,
                    "minecraft:honey_block":7,
                    "minecraft:comparator":3,
                    "minecraft:repeater":5,
                    "minecraft:observer":2,
                    "minecraft:crafting_table":99
                  },
                  "minecraft:mined":{
                    "minecraft:clay":29,
                    "minecraft:lectern":11
                  },
                  "minecraft:used":{
                    "minecraft:egg":13,
                    "minecraft:brown_egg":17,
                    "minecraft:blue_egg":19,
                    "minecraft:snowball":97
                  }
                }}
                """).getAsJsonObject();
        JsonObject customMetrics = new JsonObject();
        customMetrics.addProperty("xp_level", 42);

        AwardEvaluator evaluator = new AwardEvaluator(definitions);
        Map<String, Double> scores = evaluator.evaluate(stats, customMetrics);

        check(scores.get("collect_potato") == 23d, "Absolute Spud read the wrong score");
        check(scores.get("craft_bricks") == 37d,
                "Bricklayer did not count only crafted brick blocks");
        check(scores.get("craft_honey_block") == 7d, "Honey I'm Home read the wrong score");
        check(scores.get("craft_redstone_components") == 10d,
                "Redstone Crafter did not sum only redstone components");
        check(scores.get("mine_ground") == 29d,
                "Excavator did not count mined clay");
        check(scores.get("mine_lectern") == 11d, "Librarian Reroller read the wrong score");
        check(scores.get("use_egg") == 49d,
                "Egg Tosser did not sum normal, brown, and blue eggs");
        check(scores.get("xp_level") == 42d,
                "Level Headed did not read the custom XP level");

        JsonObject zeroMetrics = new JsonObject();
        zeroMetrics.addProperty("xp_level", 0);
        Map<String, Double> zeroScores = evaluator.evaluate(stats, zeroMetrics);
        check(zeroScores.containsKey("xp_level") && zeroScores.get("xp_level") == 0d,
                "an explicit zero XP level was treated as missing");

        Map<String, Double> missingScores = evaluator.evaluate(stats, new JsonObject());
        check(!missingScores.containsKey("xp_level"),
                "a missing XP level would overwrite the last valid score");
        check(missingScores.get("collect_potato") == 23d,
                "missing custom metrics suppressed vanilla award scores");

        JsonObject invalidMetrics = new JsonObject();
        invalidMetrics.addProperty("xp_level", "42");
        check(!evaluator.evaluate(stats, invalidMetrics).containsKey("xp_level"),
                "a non-numeric XP level was accepted");
    }

    private static Map<String, JsonObject> loadSeedRows() throws Exception {
        Map<String, JsonObject> rowsById = new HashMap<>();
        try (var input = AwardNewDefinitionsRegressionTest.class
                .getResourceAsStream("/crabcraft/awards.json")) {
            check(input != null, "bundled awards seed is missing");
            JsonArray rows = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : rows) {
                JsonObject row = element.getAsJsonObject();
                String id = row.get("id").getAsString();
                if (id.equals("collect_potato")
                        || id.equals("craft_bricks")
                        || id.equals("craft_honey_block")
                        || id.equals("craft_redstone_components")
                        || id.equals("mine_ground")
                        || id.equals("mine_lectern")
                        || id.equals("use_egg")
                        || id.equals("xp_level")) {
                    rowsById.put(id, row);
                }
            }
        }
        check(rowsById.size() == 8, "one or more award definitions are missing");
        return rowsById;
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
        if (reader.has("patterns")) {
            definition.reader.patterns = reader.getAsJsonArray("patterns").asList().stream()
                    .map(element -> element.getAsString())
                    .toList();
        }
        return definition;
    }

    private static void checkTitle(Map<String, JsonObject> rows, String id, String title) {
        check(rows.containsKey(id), id + " is missing from the award seed");
        check(rows.get(id).get("title").getAsString().equals(title),
                id + " has an unexpected title");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
