package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Collections
import java.util.OptionalDouble
import java.util.regex.Pattern

/** Evaluates vanilla, custom-int and set-count award readers; missing optional scores are omitted. */
class AwardEvaluator(private val definitions: Map<String, AwardDefinition>) {
    private val patternCache = HashMap<String?, List<Pattern>>()

    fun evaluate(rawStats: JsonObject?): Map<String, Double> = evaluate(rawStats, null, null)
    fun evaluate(rawStats: JsonObject?, customMetrics: JsonObject?): Map<String, Double> = evaluate(rawStats, customMetrics, null)

    @Suppress("UNCHECKED_CAST")
    fun evaluate(rawStats: JsonObject?, customMetrics: JsonObject?, advancements: JsonObject?): Map<String, Double> {
        val stats = if (rawStats == null) JsonObject() else if (rawStats.has("stats") && rawStats.get("stats").isJsonObject)
            rawStats.getAsJsonObject("stats") else rawStats
        val fullPayload = JsonObject()
        if (advancements != null) fullPayload.add("advancements", advancements)
        val out = HashMap<String?, Double>(definitions.size)
        for (def in definitions.values) {
            val reader = def.reader
            if (reader != null && "custom-int" == reader.type) {
                readOptionalNumber(customMetrics, reader.path).ifPresent { value -> out[def.id] = value }
                continue
            }
            if (reader != null && "set-count" == reader.type) {
                readOptionalObjectSize(fullPayload, reader.path).ifPresent { value -> out[def.id] = value }
                continue
            }
            out[def.id] = evaluateOne(def, stats)
        }
        return out as Map<String, Double>
    }

    private fun evaluateOne(def: AwardDefinition, stats: JsonObject): Double {
        val reader = def.reader ?: return 0.0
        val type = reader.type ?: return 0.0
        val path = reader.path ?: return 0.0
        return when (type) {
            "int" -> readInt(stats, path)
            "match-sum" -> readMatchSum(stats, path, compilePatterns(def.id, reader.patterns))
            else -> 0.0
        }
    }

    private fun compilePatterns(awardId: String?, rawPatterns: List<String>?): List<Pattern> {
        val cached = patternCache[awardId]
        if (cached != null) return cached
        val compiled = ArrayList<Pattern>(rawPatterns?.size ?: 0)
        if (rawPatterns != null) {
            for (raw in rawPatterns) {
                // Anchor to full match, matching the upstream MinecraftStats semantics.
                compiled.add(Pattern.compile("^" + raw + "$"))
            }
        }
        val frozen = Collections.unmodifiableList(compiled)
        patternCache[awardId] = frozen
        return frozen
    }

    companion object {
        private fun readInt(stats: JsonObject, path: List<String>): Double {
            var cursor: JsonElement? = stats
            for (segment in path) {
                if (cursor == null || !cursor.isJsonObject) return 0.0
                cursor = cursor.asJsonObject.get(segment)
            }
            if (cursor == null) return 0.0
            if (cursor.isJsonPrimitive && cursor.asJsonPrimitive.isNumber) return cursor.asDouble
            return 0.0
        }

        private fun readOptionalNumber(source: JsonObject?, path: List<String>?): OptionalDouble {
            if (source == null || path == null) return OptionalDouble.empty()
            var cursor: JsonElement? = source
            for (segment in path) {
                if (cursor == null || !cursor.isJsonObject) return OptionalDouble.empty()
                cursor = cursor.asJsonObject.get(segment)
            }
            return if (cursor != null && cursor.isJsonPrimitive && cursor.asJsonPrimitive.isNumber)
                OptionalDouble.of(cursor.asDouble) else OptionalDouble.empty()
        }

        private fun readOptionalObjectSize(source: JsonObject?, path: List<String>?): OptionalDouble {
            if (source == null || path == null) return OptionalDouble.empty()
            var cursor: JsonElement? = source
            for (segment in path) {
                if (cursor == null || !cursor.isJsonObject) return OptionalDouble.empty()
                cursor = cursor.asJsonObject.get(segment)
            }
            return if (cursor != null && cursor.isJsonObject) OptionalDouble.of(cursor.asJsonObject.size().toDouble())
                else OptionalDouble.empty()
        }

        private fun readMatchSum(stats: JsonObject, path: List<String>, patterns: List<Pattern>): Double {
            if (patterns.isEmpty()) return 0.0
            var cursor: JsonElement? = stats
            for (segment in path) {
                if (cursor == null || !cursor.isJsonObject) return 0.0
                cursor = cursor.asJsonObject.get(segment)
            }
            if (cursor == null || !cursor.isJsonObject) return 0.0
            var sum = 0.0
            for (entry in cursor.asJsonObject.entrySet()) {
                if (!matchesAny(entry.key, patterns)) continue
                val value = entry.value
                if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) sum += value.asDouble
            }
            return sum
        }

        private fun matchesAny(key: String, patterns: List<Pattern>): Boolean {
            for (pattern in patterns) if (pattern.matcher(key).matches()) return true
            return false
        }
    }
}
