package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class AwardHarvestDefinitionsRegressionTest {
    public static void main(String[] args) throws Exception {
        Set<String> ids = Set.of("craft_honeycomb_block", "use_honeycomb", "harvest_honeycomb", "place_cake",
                "harvest_cocoa", "craft_golden_apple", "harvest_ink_sac", "harvest_glow_ink_sac");
        Map<String, AwardDefinition> definitions = new HashMap<>();
        try (var input = AwardHarvestDefinitionsRegressionTest.class.getResourceAsStream("/crabcraft/awards.json")) {
            check(input != null, "bundled awards seed is missing");
            JsonArray rows = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : rows) {
                JsonObject row = element.getAsJsonObject();
                String id = row.get("id").getAsString();
                if (!ids.contains(id)) continue;
                AwardDefinition definition = new AwardDefinition();
                definition.id = id;
                JsonObject reader = row.getAsJsonObject("reader");
                definition.reader = new AwardDefinition.Reader();
                definition.reader.type = reader.get("type").getAsString();
                definition.reader.path = reader.getAsJsonArray("path").asList().stream()
                        .map(segment -> segment.getAsString()).toList();
                check(definitions.put(id, definition) == null, "duplicate award: " + id);
            }
        }
        check(definitions.keySet().equals(ids), "one or more requested award definitions are missing");

        JsonObject stats = JsonParser.parseString("""
                {"stats":{
                  "minecraft:crafted":{
                    "minecraft:honeycomb_block":7,"minecraft:honey_block":80,
                    "minecraft:golden_apple":11,"minecraft:enchanted_golden_apple":90,"minecraft:cake":99
                  },
                  "minecraft:used":{"minecraft:honeycomb":13,"minecraft:honey_bottle":100,"minecraft:cake":17},
                  "minecraft:picked_up":{
                    "minecraft:honeycomb":500,"minecraft:cocoa_beans":600,
                    "minecraft:ink_sac":700,"minecraft:glow_ink_sac":800
                  },
                  "minecraft:mined":{"minecraft:cocoa":900},
                  "minecraft:killed":{"minecraft:squid":1000,"minecraft:glow_squid":1100}
                }}
                """).getAsJsonObject();
        AwardEvaluator evaluator = new AwardEvaluator(definitions);
        Map<String, Double> withoutCustom = evaluator.evaluate(stats);
        check(withoutCustom.equals(Map.of("craft_honeycomb_block", 7d, "craft_golden_apple", 11d,
                "use_honeycomb", 13d, "place_cake", 17d)),
                "crafting/placement/use paths or omission of unverified harvests are wrong");

        JsonObject custom = new JsonObject();
        custom.addProperty("harvest_honeycomb", 3);
        custom.addProperty("harvest_cocoa", 6);
        custom.addProperty("harvest_ink_sac", 4);
        custom.addProperty("harvest_glow_ink_sac", 0);
        Map<String, Double> scores = evaluator.evaluate(stats, custom);
        check(scores.size() == 8 && scores.get("harvest_honeycomb") == 3d
                        && scores.get("harvest_cocoa") == 6d && scores.get("harvest_ink_sac") == 4d
                        && scores.get("harvest_glow_ink_sac") == 0d,
                "harvest awards must read confirmed item quantities, including explicit zero");
        custom.remove("harvest_cocoa");
        check(!evaluator.evaluate(stats, custom).containsKey("harvest_cocoa"),
                "missing saved harvest data must preserve the last valid score");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
