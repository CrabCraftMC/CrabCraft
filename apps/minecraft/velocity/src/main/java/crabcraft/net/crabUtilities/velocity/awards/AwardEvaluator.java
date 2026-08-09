package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Evaluates a set of {@link AwardDefinition}s against a parsed Minecraft
 * stats JSON (the raw {@code stats/<uuid>.json} content).
 *
 * <p>Standard readers match the upstream MinecraftStats schema:
 * <ul>
 *     <li>{@code int} — read a single integer at {@code reader.path}.</li>
 *     <li>{@code match-sum} — navigate to {@code reader.path} (a JSON
 *         object) and sum every value whose key matches any of the
 *         {@code reader.patterns} regexes (anchored with {@code ^...$}).</li>
 *     <li>{@code custom-int} — read an optional numeric value from the
 *         plugin-provided custom metrics object. Missing values are omitted
 *         from the result so they do not overwrite the last valid score.</li>
 *     <li>{@code set-count} — count keys in an object from the full stats
 *         payload, including advancement criteria. Missing values are omitted.</li>
 * </ul>
 * Unknown reader types are treated as zero and skipped with a silent miss,
 * to keep evaluation total across all configured awards even if upstream adds new
 * reader kinds.
 */
public final class AwardEvaluator {

    private final Map<String, AwardDefinition> definitions;
    private final Map<String, List<Pattern>> patternCache = new HashMap<>();

    public AwardEvaluator(Map<String, AwardDefinition> definitions) {
        this.definitions = definitions;
    }

    /**
     * Evaluate every award against the given stats JSON.
     *
     * @param rawStats the root object of a Minecraft stats file. Handles
     *                 both the 1.15+ shape ({@code {"stats": {...}}}) and
     *                 an already-unwrapped inner object.
     * @return awardId &rarr; numeric score (0 for missing / unmatched).
     */
    public Map<String, Double> evaluate(JsonObject rawStats) {
        return evaluate(rawStats, null, null);
    }

    /**
     * Evaluate every award against vanilla stats and optional custom metrics.
     *
     * @param rawStats the root object of a Minecraft stats file
     * @param customMetrics optional plugin-provided metrics
     * @return awardId &rarr; numeric score; missing custom values are omitted
     */
    public Map<String, Double> evaluate(JsonObject rawStats, JsonObject customMetrics) {
        return evaluate(rawStats, customMetrics, null);
    }

    /**
     * Evaluate every award against vanilla stats, custom metrics, and advancements.
     *
     * @param rawStats the root object of a Minecraft stats file
     * @param customMetrics optional plugin-provided metrics
     * @param advancements optional root object of a Minecraft advancements file
     * @return awardId &rarr; numeric score; missing optional values are omitted
     */
    public Map<String, Double> evaluate(
            JsonObject rawStats, JsonObject customMetrics, JsonObject advancements) {
        JsonObject stats = rawStats == null ? new JsonObject()
                : (rawStats.has("stats") && rawStats.get("stats").isJsonObject()
                        ? rawStats.getAsJsonObject("stats")
                        : rawStats);
        JsonObject fullPayload = new JsonObject();
        if (advancements != null) fullPayload.add("advancements", advancements);

        Map<String, Double> out = new HashMap<>(definitions.size());
        for (AwardDefinition def : definitions.values()) {
            if (def.reader != null && "custom-int".equals(def.reader.type)) {
                readOptionalNumber(customMetrics, def.reader.path)
                        .ifPresent(value -> out.put(def.id, value));
                continue;
            }
            if (def.reader != null && "set-count".equals(def.reader.type)) {
                readOptionalObjectSize(fullPayload, def.reader.path)
                        .ifPresent(value -> out.put(def.id, value));
                continue;
            }
            out.put(def.id, evaluateOne(def, stats));
        }
        return out;
    }

    private double evaluateOne(AwardDefinition def, JsonObject stats) {
        AwardDefinition.Reader reader = def.reader;
        if (reader == null || reader.type == null || reader.path == null) return 0d;

        switch (reader.type) {
            case "int":
                return readInt(stats, reader.path);
            case "match-sum":
                return readMatchSum(stats, reader.path, compilePatterns(def.id, reader.patterns));
            default:
                return 0d;
        }
    }

    private static double readInt(JsonObject stats, List<String> path) {
        JsonElement cursor = stats;
        for (String segment : path) {
            if (cursor == null || !cursor.isJsonObject()) return 0d;
            cursor = cursor.getAsJsonObject().get(segment);
        }
        if (cursor == null) return 0d;
        if (cursor.isJsonPrimitive() && cursor.getAsJsonPrimitive().isNumber()) {
            return cursor.getAsDouble();
        }
        return 0d;
    }

    private static OptionalDouble readOptionalNumber(JsonObject source, List<String> path) {
        if (source == null || path == null) return OptionalDouble.empty();

        JsonElement cursor = source;
        for (String segment : path) {
            if (cursor == null || !cursor.isJsonObject()) return OptionalDouble.empty();
            cursor = cursor.getAsJsonObject().get(segment);
        }
        if (cursor != null
                && cursor.isJsonPrimitive()
                && cursor.getAsJsonPrimitive().isNumber()) {
            return OptionalDouble.of(cursor.getAsDouble());
        }
        return OptionalDouble.empty();
    }

    private static OptionalDouble readOptionalObjectSize(JsonObject source, List<String> path) {
        if (source == null || path == null) return OptionalDouble.empty();

        JsonElement cursor = source;
        for (String segment : path) {
            if (cursor == null || !cursor.isJsonObject()) return OptionalDouble.empty();
            cursor = cursor.getAsJsonObject().get(segment);
        }
        return cursor != null && cursor.isJsonObject()
                ? OptionalDouble.of(cursor.getAsJsonObject().size())
                : OptionalDouble.empty();
    }

    private static double readMatchSum(JsonObject stats, List<String> path, List<Pattern> patterns) {
        if (patterns.isEmpty()) return 0d;
        JsonElement cursor = stats;
        for (String segment : path) {
            if (cursor == null || !cursor.isJsonObject()) return 0d;
            cursor = cursor.getAsJsonObject().get(segment);
        }
        if (cursor == null || !cursor.isJsonObject()) return 0d;

        double sum = 0d;
        for (Map.Entry<String, JsonElement> entry : cursor.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (!matchesAny(key, patterns)) continue;
            JsonElement val = entry.getValue();
            if (val != null && val.isJsonPrimitive() && val.getAsJsonPrimitive().isNumber()) {
                sum += val.getAsDouble();
            }
        }
        return sum;
    }

    private static boolean matchesAny(String key, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(key).matches()) return true;
        }
        return false;
    }

    private List<Pattern> compilePatterns(String awardId, List<String> rawPatterns) {
        List<Pattern> cached = patternCache.get(awardId);
        if (cached != null) return cached;
        List<Pattern> compiled = new ArrayList<>(rawPatterns == null ? 0 : rawPatterns.size());
        if (rawPatterns != null) {
            for (String raw : rawPatterns) {
                // Anchor to full match, matching the upstream MinecraftStats semantics.
                compiled.add(Pattern.compile("^" + raw + "$"));
            }
        }
        List<Pattern> frozen = Collections.unmodifiableList(compiled);
        patternCache.put(awardId, frozen);
        return frozen;
    }
}
