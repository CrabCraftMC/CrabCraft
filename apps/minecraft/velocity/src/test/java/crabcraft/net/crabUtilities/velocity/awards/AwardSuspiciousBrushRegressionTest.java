package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class AwardSuspiciousBrushRegressionTest {

    public static void main(String[] args) throws Exception {
        JsonObject row = loadSeedRow();
        check(row.get("title").getAsString().equals("Archaeologist"),
                "unexpected suspicious-brushing award title");
        check(row.get("icon").getAsString().equals("/awards/icons/brush_suspicious.png"),
                "suspicious-brushing award icon changed");

        JsonObject reader = row.getAsJsonObject("reader");
        AwardDefinition definition = new AwardDefinition();
        definition.id = row.get("id").getAsString();
        definition.reader = new AwardDefinition.Reader();
        definition.reader.type = reader.get("type").getAsString();
        definition.reader.path = reader.getAsJsonArray("path").asList().stream()
                .map(element -> element.getAsString())
                .toList();
        definition.reader.patterns = reader.getAsJsonArray("patterns").asList().stream()
                .map(element -> element.getAsString())
                .toList();

        double score = new AwardEvaluator(Map.of(definition.id, definition)).evaluate(
                JsonParser.parseString("""
                        {"stats":{"minecraft:mined":{
                          "minecraft:suspicious_sand":7,
                          "minecraft:suspicious_gravel":5,
                          "minecraft:sand":64,
                          "minecraft:gravel":32
                        }}}
                        """).getAsJsonObject()).get(definition.id);

        check(score == 12d, "only brushed suspicious sand and gravel should be summed");
    }

    private static JsonObject loadSeedRow() throws Exception {
        try (var input = AwardSuspiciousBrushRegressionTest.class
                .getResourceAsStream("/crabcraft/awards.json")) {
            check(input != null, "bundled awards seed is missing");
            JsonArray rows = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : rows) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("id").getAsString().equals("brush_suspicious")) return row;
            }
        }
        throw new AssertionError("brush_suspicious is missing from the bundled awards seed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
