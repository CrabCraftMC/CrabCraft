package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Random;

public final class AwardEatingRegressionTest {
    public static void main(String[] args) throws Exception {
        var definitions = new HashMap<String, AwardDefinition>();
        try (var input = AwardEatingRegressionTest.class.getResourceAsStream("/crabcraft/awards.json")) {
            check(input != null, "Bundled awards seed is missing");
            for (var row : JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray()) {
                String id = row.getAsJsonObject().get("id").getAsString();
                if (!id.startsWith("eat_")) continue;
                var definition = new Gson().fromJson(row, AwardDefinition.class);
                definition.reader.type = row.getAsJsonObject().getAsJsonObject("reader").get("type").getAsString();
                definitions.put(id, definition);
                check(id.equals("eat_cake") ? definition.reader.type.equals("int")
                        : definition.reader.type.equals("custom-int"), "Wrong food reader: " + id);
            }
        }
        check(definitions.size() == 10, "An eating award is missing");
        var evaluator = new AwardEvaluator(definitions);
        Random random = new Random(362_591L);
        JsonObject used = new JsonObject();
        for (String food : new String[]{"potato", "carrot", "bread", "cake", "sweet_berries", "cooked_beef", "cookie"}) {
            used.addProperty("minecraft:" + food, 100 + random.nextInt(900));
        }
        JsonObject stats = new JsonObject();
        stats.add("minecraft:used", used);
        JsonObject vanillaCustom = new JsonObject();
        int slices = 1 + random.nextInt(90);
        vanillaCustom.addProperty("minecraft:eat_cake_slice", slices);
        stats.add("minecraft:custom", vanillaCustom);
        var missing = evaluator.evaluate(stats);
        check(missing.size() == 1 && missing.get("eat_cake") == slices, "Legacy uses reinstated eating scores or cake stopped working");
        JsonObject custom = new JsonObject();
        definitions.keySet().stream().filter(id -> !id.equals("eat_cake"))
                .forEach(id -> custom.addProperty(id, 1 + random.nextInt(90)));
        var scores = evaluator.evaluate(stats, custom);
        custom.entrySet().forEach(entry -> check(scores.get(entry.getKey()) == entry.getValue().getAsDouble(), "Wrong confirmed total"));
        custom.addProperty("eat_veggie", 0);
        check(evaluator.evaluate(stats, custom).get("eat_veggie") == 0, "Verified zero did not replace old score");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
