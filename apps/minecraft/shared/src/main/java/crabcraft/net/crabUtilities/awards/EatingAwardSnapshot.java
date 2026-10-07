package crabcraft.net.crabUtilities.awards;

import com.google.gson.JsonObject;

import java.util.Set;

/** A cumulative meal counter, with an optional verified pre-tracking total. */
public record EatingAwardSnapshot(long trackingStartedAt, long capturedAt, long meals, Long historicalScore) {
    public static final Set<String> AWARDS = Set.of("eat_bread", "eat_cookie", "eat_fish", "eat_junkfood",
            "eat_meat", "eat_rawmeat", "eat_soup", "eat_veggie", "eat_sweet_berries");

    public EatingAwardSnapshot {
        if (trackingStartedAt <= 0 || capturedAt < trackingStartedAt || meals < 0
                || (historicalScore != null && historicalScore < 0)) {
            throw new IllegalArgumentException("Invalid eating-award snapshot");
        }
        if (historicalScore != null) Math.addExact(historicalScore, meals);
    }

    public boolean sameProgress(EatingAwardSnapshot other) {
        return other != null && trackingStartedAt == other.trackingStartedAt && meals == other.meals
                && java.util.Objects.equals(historicalScore, other.historicalScore);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("trackingStartedAt", trackingStartedAt);
        json.addProperty("capturedAt", capturedAt);
        json.addProperty("meals", meals);
        if (historicalScore != null) json.addProperty("historicalScore", historicalScore);
        return json;
    }

    public static EatingAwardSnapshot fromJson(JsonObject json) {
        return new EatingAwardSnapshot(number(json, "trackingStartedAt"), number(json, "capturedAt"),
                number(json, "meals"), json.has("historicalScore") ? number(json, "historicalScore") : null);
    }

    private static long number(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid eating-award field: " + key);
        }
        return value.getAsBigDecimal().longValueExact();
    }
}
