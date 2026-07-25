package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class AwardSweetBerriesRegressionTest {

    public static void main(String[] args) throws Exception {
        JsonObject row = loadSeedRow();
        check(row.get("title").getAsString().equals("Jam Buds"),
                "unexpected sweet-berries award title");

        AwardDefinition definition = definitionFrom(row);
        double score = new AwardEvaluator(Map.of(definition.id, definition)).evaluate(
                JsonParser.parseString("""
                        {"stats":{
                          "minecraft:used":{
                            "minecraft:sweet_berries":13,
                            "minecraft:glow_berries":99
                          }
                        }}
                        """).getAsJsonObject()).get(definition.id);

        check(score == 13d,
                "Jam Buds did not read only sweet berries eaten");
    }

    private static JsonObject loadSeedRow() throws Exception {
        try (var input = AwardSweetBerriesRegressionTest.class
                .getResourceAsStream("/crabcraft/awards.json")) {
            check(input != null, "bundled awards seed is missing");
            JsonArray rows = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : rows) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("id").getAsString().equals("eat_sweet_berries")) return row;
            }
        }
        throw new AssertionError("eat_sweet_berries is missing from the award seed");
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
