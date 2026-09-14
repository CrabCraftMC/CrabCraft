package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonParser

object AwardDyeCraftingRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val definition = AwardDefinition()
        definition.id = "craft_dye"
        definition.reader = AwardDefinition.Reader()
        definition.reader!!.type = "match-sum"
        definition.reader!!.path = listOf("minecraft:crafted")
        definition.reader!!.patterns = listOf("minecraft:.+_dye")

        val score = AwardEvaluator(mapOf(definition.id!! to definition)).evaluate(
                JsonParser.parseString("""
                        {"stats":{"minecraft:crafted":{
                          "minecraft:red_dye":16,
                          "minecraft:blue_dye":24,
                          "minecraft:white_dye":8,
                          "minecraft:red_wool":64,
                          "example:red_dye":100
                        }}}
                        """).asJsonObject).get(definition.id)

        check(score == 48.0, "dye output quantities were not summed exactly once")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
