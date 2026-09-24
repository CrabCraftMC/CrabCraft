package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Random

object AwardEatingRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val definitions = HashMap<String, AwardDefinition>()
        AwardEatingRegressionTest::class.java.getResourceAsStream("/crabcraft/awards.json").use { input ->
            check(input != null, "Bundled awards seed is missing")
            for (row in JsonParser.parseReader(InputStreamReader(input, StandardCharsets.UTF_8)).asJsonArray) {
                val id = row.asJsonObject.get("id").asString
                if (!id.startsWith("eat_")) continue
                val definition = Gson().fromJson(row, AwardDefinition::class.java)
                val reader = definition.reader!!
                reader.type = row.asJsonObject.getAsJsonObject("reader").get("type").asString
                definitions[id] = definition
                check(
                    if (id == "eat_cake") reader.type == "int" else reader.type == "custom-int",
                    "Wrong food reader: " + id,
                )
            }
        }
        check(definitions.size == 10, "An eating award is missing")
        val evaluator = AwardEvaluator(definitions)
        val random = Random(362_591L)
        val used = JsonObject()
        for (food in arrayOf("potato", "carrot", "bread", "cake", "sweet_berries", "cooked_beef", "cookie")) {
            used.addProperty("minecraft:" + food, 100 + random.nextInt(900))
        }
        val stats = JsonObject()
        stats.add("minecraft:used", used)
        val vanillaCustom = JsonObject()
        val slices = 1 + random.nextInt(90)
        vanillaCustom.addProperty("minecraft:eat_cake_slice", slices)
        stats.add("minecraft:custom", vanillaCustom)
        val missing = evaluator.evaluate(stats)
        check(
            missing.size == 1 && missing["eat_cake"] == slices.toDouble(),
            "Legacy uses reinstated eating scores or cake stopped working",
        )
        val custom = JsonObject()
        definitions.keys.filter { it != "eat_cake" }.forEach { custom.addProperty(it, 1 + random.nextInt(90)) }
        val scores = evaluator.evaluate(stats, custom)
        custom.entrySet().forEach { check(scores[it.key] == it.value.asDouble, "Wrong confirmed total") }
        custom.addProperty("eat_veggie", 0)
        check(evaluator.evaluate(stats, custom)["eat_veggie"] == 0.0, "Verified zero did not replace old score")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
