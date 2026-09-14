package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object AwardSuspiciousBrushRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val row = loadSeedRow()
        check(row.get("title").asString.equals("Archaeologist"),
                "unexpected suspicious-brushing award title")
        check(row.get("icon").asString.equals("/awards/icons/brush_suspicious.png"),
                "suspicious-brushing award icon changed")

        val reader = row.getAsJsonObject("reader")
        val definition = AwardDefinition()
        definition.id = row.get("id").asString
        definition.reader = AwardDefinition.Reader()
        definition.reader!!.type = reader.get("type").asString
        definition.reader!!.path = reader.getAsJsonArray("path").asList().stream()
                .map { element -> element.asString }
                .toList()
        definition.reader!!.patterns = reader.getAsJsonArray("patterns").asList().stream()
                .map { element -> element.asString }
                .toList()

        val score = AwardEvaluator(mapOf(definition.id!! to definition)).evaluate(
                JsonParser.parseString("""
                        {"stats":{"minecraft:mined":{
                          "minecraft:suspicious_sand":7,
                          "minecraft:suspicious_gravel":5,
                          "minecraft:sand":64,
                          "minecraft:gravel":32
                        }}}
                        """).asJsonObject).get(definition.id)

        check(score == 12.0, "only brushed suspicious sand and gravel should be summed")
    }

    private fun loadSeedRow(): JsonObject {
        AwardSuspiciousBrushRegressionTest::class.java.getResourceAsStream("/crabcraft/awards.json").use { input ->
            check(input != null, "bundled awards seed is missing")
            val rows = JsonParser.parseReader(
                    InputStreamReader(input!!, StandardCharsets.UTF_8)).asJsonArray
            for (element in rows) {
                val row = element.asJsonObject
                if (row.get("id").asString.equals("brush_suspicious")) return row
            }
        }
        throw AssertionError("brush_suspicious is missing from the bundled awards seed")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
