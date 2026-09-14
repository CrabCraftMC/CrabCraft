package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.HashMap

object AwardNewDefinitionsRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val rows = loadSeedRows()
        checkTitle(rows, "collect_potato", "Absolute Spud")
        checkTitle(rows, "craft_bricks", "Bricklayer")
        checkTitle(rows, "craft_honey_block", "Honey I'm Home")
        checkTitle(rows, "craft_redstone_components", "Redstone Crafter")
        checkTitle(rows, "mine_ground", "Excavator")
        checkTitle(rows, "mine_lectern", "Librarian Reroller")
        checkTitle(rows, "xp_level", "Level Headed")
        check(rows.getValue("xp_level").getAsJsonObject("reader").get("type").asString
                        .equals("custom-int"),
                "Level Headed does not use the custom reader")

        val definitions = HashMap<String, AwardDefinition>()
        for (row in rows.values) {
            val definition = definitionFrom(row)
            definitions.put(definition.id!!, definition)
        }

        val stats = JsonParser.parseString("""
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
                """).asJsonObject
        val customMetrics = JsonObject()
        customMetrics.addProperty("xp_level", 42)

        val evaluator = AwardEvaluator(definitions)
        val scores = evaluator.evaluate(stats, customMetrics)

        check(scores.get("collect_potato") == 23.0, "Absolute Spud read the wrong score")
        check(scores.get("craft_bricks") == 37.0,
                "Bricklayer did not count only crafted brick blocks")
        check(scores.get("craft_honey_block") == 7.0, "Honey I'm Home read the wrong score")
        check(scores.get("craft_redstone_components") == 10.0,
                "Redstone Crafter did not sum only redstone components")
        check(scores.get("mine_ground") == 29.0,
                "Excavator did not count mined clay")
        check(scores.get("mine_lectern") == 11.0, "Librarian Reroller read the wrong score")
        check(scores.get("use_egg") == 49.0,
                "Egg Tosser did not sum normal, brown, and blue eggs")
        check(scores.get("xp_level") == 42.0,
                "Level Headed did not read the custom XP level")

        val zeroMetrics = JsonObject()
        zeroMetrics.addProperty("xp_level", 0)
        val zeroScores = evaluator.evaluate(stats, zeroMetrics)
        check(zeroScores.containsKey("xp_level") && zeroScores.get("xp_level") == 0.0,
                "an explicit zero XP level was treated as missing")

        val missingScores = evaluator.evaluate(stats, JsonObject())
        check(!missingScores.containsKey("xp_level"),
                "a missing XP level would overwrite the last valid score")
        check(missingScores.get("collect_potato") == 23.0,
                "missing custom metrics suppressed vanilla award scores")

        val invalidMetrics = JsonObject()
        invalidMetrics.addProperty("xp_level", "42")
        check(!evaluator.evaluate(stats, invalidMetrics).containsKey("xp_level"),
                "a non-numeric XP level was accepted")
    }

    private fun loadSeedRows(): Map<String, JsonObject> {
        val rowsById = HashMap<String, JsonObject>()
        AwardNewDefinitionsRegressionTest::class.java.getResourceAsStream("/crabcraft/awards.json").use { input ->
            check(input != null, "bundled awards seed is missing")
            val rows = JsonParser.parseReader(
                    InputStreamReader(input!!, StandardCharsets.UTF_8)).asJsonArray
            for (element in rows) {
                val row = element.asJsonObject
                val id = row.get("id").asString
                if (id.equals("collect_potato")
                        || id.equals("craft_bricks")
                        || id.equals("craft_honey_block")
                        || id.equals("craft_redstone_components")
                        || id.equals("mine_ground")
                        || id.equals("mine_lectern")
                        || id.equals("use_egg")
                        || id.equals("xp_level")) {
                    rowsById.put(id, row)
                }
            }
        }
        check(rowsById.size == 8, "one or more award definitions are missing")
        return rowsById
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
        if (reader.has("patterns")) {
            definition.reader!!.patterns = reader.getAsJsonArray("patterns").asList().stream()
                    .map { element -> element.asString }
                    .toList()
        }
        return definition
    }

    private fun checkTitle(rows: Map<String, JsonObject>, id: String, title: String) {
        check(rows.containsKey(id), id + " is missing from the award seed")
        check(rows.getValue(id).get("title").asString.equals(title),
                id + " has an unexpected title")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
