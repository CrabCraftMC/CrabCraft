package crabcraft.net.crabUtilities.velocity.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AdvancementRegistry {

    private final Map<String, JsonObject> advancements;

    public AdvancementRegistry(Logger logger) {
        Map<String, JsonObject> map = new LinkedHashMap<>();
        try (InputStream is = getClass().getResourceAsStream("/advancements.json")) {
            if (is == null) {
                logger.error("advancements.json not found in plugin resources");
            } else {
                JsonArray arr = JsonParser.parseReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    map.put(obj.get("id").getAsString(), obj);
                }
                logger.info("Loaded {} advancement definitions from registry", map.size());
            }
        } catch (Exception e) {
            logger.error("Failed to load advancements.json", e);
        }
        this.advancements = Collections.unmodifiableMap(map);
    }

    public Map<String, JsonObject> getAll() {
        return advancements;
    }

    public int getTotal() {
        return advancements.size();
    }

    public int getTotalForCategory(String category) {
        String prefix = "minecraft:" + category + "/";
        return (int) advancements.keySet().stream()
                .filter(id -> id.startsWith(prefix))
                .count();
    }

    private static final java.util.Set<String> VALID_CATEGORIES =
            java.util.Set.of("story", "nether", "end", "adventure", "husbandry");

    public boolean isValidCategory(String category) {
        return category != null && VALID_CATEGORIES.contains(category);
    }
}
