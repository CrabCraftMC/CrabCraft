package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonObject
import java.util.Random

object AwardEvaluatorRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val vanilla = definition("vanilla", "int", listOf("crafted", "synthetic_item"))
        val sum = definition("sum", "match-sum", listOf("crafted"))
        sum.reader!!.patterns = listOf("synthetic_.+", "synthetic_item")
        val custom = definition("custom", "custom-int", listOf("confirmed"))
        val criteria = definition("criteria", "set-count", listOf("advancements", "visited"))
        val evaluator =
            AwardEvaluator(
                mapOf(
                    vanilla.id!! to vanilla,
                    sum.id!! to sum,
                    custom.id!! to custom,
                    criteria.id!! to criteria,
                )
            )
        val random = Random(932_617L)
        val first = 1 + random.nextInt(100)
        val second = 1 + random.nextInt(100)
        val crafted = JsonObject()
        crafted.addProperty("synthetic_item", first)
        crafted.addProperty("synthetic_other", second)
        crafted.addProperty("unmatched", 1 + random.nextInt(100))
        crafted.addProperty("synthetic_invalid", "invalid")
        val stats = JsonObject()
        stats.add("crafted", crafted)
        val wrapped = JsonObject()
        wrapped.add("stats", stats)
        val missing = evaluator.evaluate(wrapped)
        check(missing["vanilla"] == first.toDouble(), "vanilla reader returned the wrong value")
        check(
            missing["sum"] == (first + second).toDouble(),
            "matching values were omitted, counted twice or mixed with invalid values",
        )
        check(
            !missing.containsKey("custom") && !missing.containsKey("criteria"),
            "missing optional data would overwrite previously stored scores",
        )
        check(evaluator.evaluate(stats) == missing, "wrapped and unwrapped stats disagree")
        val metrics = JsonObject()
        metrics.addProperty("confirmed", second)
        val visited = JsonObject()
        visited.addProperty("synthetic_one", random.nextLong())
        visited.addProperty("synthetic_two", random.nextLong())
        val advancements = JsonObject()
        advancements.add("visited", visited)
        val populated = evaluator.evaluate(stats, metrics, advancements)
        check(
            populated["custom"] == second.toDouble() && populated["criteria"] == 2.0,
            "optional readers did not count confirmed values",
        )
        metrics.addProperty("confirmed", 0)
        advancements.add("visited", JsonObject())
        val zero = evaluator.evaluate(stats, metrics, advancements)
        check(
            zero["custom"] == 0.0 && zero["criteria"] == 0.0,
            "confirmed zero scores were treated as missing data",
        )
        metrics.addProperty("confirmed", second.toString())
        advancements.addProperty("visited", "invalid")
        val invalid = evaluator.evaluate(stats, metrics, advancements)
        check(
            !invalid.containsKey("custom") && !invalid.containsKey("criteria"),
            "invalid optional data would overwrite previously stored scores",
        )
    }

    private fun definition(id: String, type: String, path: List<String>): AwardDefinition =
        AwardDefinition().also { definition ->
            definition.id = id
            definition.reader =
                AwardDefinition.Reader().also {
                    it.type = type
                    it.path = path
                }
        }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
