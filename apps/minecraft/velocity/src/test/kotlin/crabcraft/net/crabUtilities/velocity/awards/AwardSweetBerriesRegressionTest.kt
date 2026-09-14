package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object AwardSweetBerriesRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val row = loadSeedRow()
        check(row.get("title").asString.equals("Jam Buds"),
                "unexpected sweet-berries award title")

        val definition = definitionFrom(row)
        val evaluator = AwardEvaluator(mapOf(definition.id!! to definition))
        val stats = JsonObject()
        val used = JsonObject()
        val random = java.util.Random(671_254L)
        used.addProperty("minecraft:sweet_berries", 100 + random.nextInt(900))
        used.addProperty("minecraft:glow_berries", 100 + random.nextInt(900))
        stats.add("minecraft:used", used)
        check(!evaluator.evaluate(stats).containsKey(definition.id),
                "missing confirmed berry totals must preserve existing scores")
        val custom = JsonObject()
        val eaten = (1 + random.nextInt(90)).toLong()
        custom.addProperty("eat_sweet_berries", eaten)
        check(evaluator.evaluate(stats, custom).get(definition.id) == eaten.toDouble(),
                "Jam Buds must read confirmed sweet berries eaten")
    }

    private fun loadSeedRow(): JsonObject {
        AwardSweetBerriesRegressionTest::class.java.getResourceAsStream("/crabcraft/awards.json").use { input ->
            check(input != null, "bundled awards seed is missing")
            val rows = JsonParser.parseReader(
                    InputStreamReader(input!!, StandardCharsets.UTF_8)).asJsonArray
            for (element in rows) {
                val row = element.asJsonObject
                if (row.get("id").asString.equals("eat_sweet_berries")) return row
            }
        }
        throw AssertionError("eat_sweet_berries is missing from the award seed")
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
