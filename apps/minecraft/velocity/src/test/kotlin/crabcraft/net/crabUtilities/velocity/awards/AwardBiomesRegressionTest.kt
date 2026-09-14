package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object AwardBiomesRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val row = loadSeedRow()
        check(row.get("title").asString.equals("Globetrotter"),
                "unexpected biomes award title")
        check(row.getAsJsonObject("reader").get("type").asString.equals("set-count"),
                "Globetrotter does not use the set-count reader")

        val definition = definitionFrom(row)
        val evaluator = AwardEvaluator(mapOf(definition.id!! to definition))
        val advancements = JsonParser.parseString("""
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
                """).asJsonObject

        val scores = evaluator.evaluate(JsonObject(), null, advancements)
        check(scores.get("biomes") == 3.0,
                "Globetrotter did not count visited biome criteria")

        val emptyCriteria = JsonParser.parseString("""
                {"minecraft:adventure/adventuring_time":{"criteria":{},"done":false}}
                """).asJsonObject
        val zeroScores = evaluator.evaluate(
                JsonObject(), null, emptyCriteria)
        check(zeroScores.containsKey("biomes") && zeroScores.get("biomes") == 0.0,
                "an empty criteria set was treated as missing advancement data")

        val missingScores = evaluator.evaluate(
                JsonObject(), null, JsonObject())
        check(!missingScores.containsKey("biomes"),
                "missing advancement data would overwrite the last valid biome count")
    }

    private fun loadSeedRow(): JsonObject {
        AwardBiomesRegressionTest::class.java.getResourceAsStream("/crabcraft/awards.json").use { input ->
            check(input != null, "bundled awards seed is missing")
            val rows = JsonParser.parseReader(
                    InputStreamReader(input!!, StandardCharsets.UTF_8)).asJsonArray
            for (element in rows) {
                val row = element.asJsonObject
                if (row.get("id").asString.equals("biomes")) return row
            }
        }
        throw AssertionError("biomes is missing from the award seed")
    }

    private fun definitionFrom(row: JsonObject): AwardDefinition {
        val reader = row.getAsJsonObject("reader")
        val definition = AwardDefinition()
        definition.id = row.get("id").asString
        definition.reader = AwardDefinition.Reader()
        definition.reader!!.type = reader.get("type").asString
        definition.reader!!.path = reader.getAsJsonArray("path").asList().stream()
                .map { element -> element.asString }
                .toList()
        return definition
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
