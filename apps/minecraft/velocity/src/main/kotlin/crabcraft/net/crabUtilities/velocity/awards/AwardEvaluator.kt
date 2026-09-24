package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.OptionalDouble
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** Evaluates configured readers against vanilla stats and optional custom metrics or advancements. */
class AwardEvaluator(private val definitions: Map<String, AwardDefinition>) {
    private val patternCache = ConcurrentHashMap<String, List<Pattern>>()

    fun evaluate(rawStats: JsonObject?): Map<String, Double> = evaluate(rawStats, null, null)

    fun evaluate(rawStats: JsonObject?, customMetrics: JsonObject?): Map<String, Double> =
        evaluate(rawStats, customMetrics, null)

    fun evaluate(rawStats: JsonObject?, customMetrics: JsonObject?, advancements: JsonObject?): Map<String, Double> {
        val stats =
            when {
                rawStats == null -> JsonObject()
                rawStats.has("stats") && rawStats.get("stats").isJsonObject -> rawStats.getAsJsonObject("stats")
                else -> rawStats
            }
        val fullPayload = JsonObject()
        if (advancements != null) fullPayload.add("advancements", advancements)
        val out = HashMap<String, Double>(definitions.size)
        for (def in definitions.values) {
            val reader = def.reader
            when (reader?.type) {
                "custom-int" -> readOptionalNumber(customMetrics, reader.path).ifPresent { out[def.id!!] = it }
                "set-count" -> readOptionalObjectSize(fullPayload, reader.path).ifPresent { out[def.id!!] = it }
                else -> out[def.id!!] = evaluateOne(def, stats)
            }
        }
        return out
    }

    private fun evaluateOne(def: AwardDefinition, stats: JsonObject): Double {
        val reader = def.reader ?: return 0.0
        if (reader.type == null || reader.path == null) return 0.0
        return when (reader.type) {
            "int" -> readInt(stats, reader.path)
            "match-sum" -> readMatchSum(stats, reader.path, compilePatterns(def.id!!, reader.patterns))
            else -> 0.0
        }
    }

    private fun readPath(source: JsonObject?, path: List<String>?): JsonElement? {
        if (path == null) return null
        var cursor: JsonElement? = source
        for (segment in path) {
            if (cursor == null || !cursor.isJsonObject) return null
            cursor = cursor.asJsonObject.get(segment)
        }
        return cursor
    }

    private fun readInt(stats: JsonObject, path: List<String>?): Double {
        val cursor = readPath(stats, path) ?: return 0.0
        return if (cursor.isJsonPrimitive && cursor.asJsonPrimitive.isNumber) cursor.asDouble else 0.0
    }

    private fun readOptionalNumber(source: JsonObject?, path: List<String>?): OptionalDouble {
        val cursor = readPath(source, path)
        return if (cursor != null && cursor.isJsonPrimitive && cursor.asJsonPrimitive.isNumber) {
            OptionalDouble.of(cursor.asDouble)
        } else OptionalDouble.empty()
    }

    private fun readOptionalObjectSize(source: JsonObject?, path: List<String>?): OptionalDouble {
        val cursor = readPath(source, path)
        return if (cursor != null && cursor.isJsonObject) OptionalDouble.of(cursor.asJsonObject.size().toDouble())
        else OptionalDouble.empty()
    }

    private fun readMatchSum(stats: JsonObject, path: List<String>?, patterns: List<Pattern>): Double {
        if (patterns.isEmpty()) return 0.0
        val cursor = readPath(stats, path) ?: return 0.0
        if (!cursor.isJsonObject) return 0.0
        var sum = 0.0
        for ((key, value) in cursor.asJsonObject.entrySet()) {
            if (patterns.none { it.matcher(key).matches() }) continue
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) sum += value.asDouble
        }
        return sum
    }

    private fun compilePatterns(awardId: String, rawPatterns: List<String>?): List<Pattern> =
        patternCache.computeIfAbsent(awardId) {
            // Anchor to full match, matching the upstream MinecraftStats semantics.
            rawPatterns?.map { Pattern.compile("^" + it + "$") } ?: emptyList()
        }
}
