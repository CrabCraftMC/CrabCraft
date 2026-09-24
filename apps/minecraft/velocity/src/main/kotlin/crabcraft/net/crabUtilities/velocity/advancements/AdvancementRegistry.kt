package crabcraft.net.crabUtilities.velocity.advancements

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import org.slf4j.Logger

class AdvancementRegistry {
    private val advancements: MutableMap<String, JsonObject>

    constructor(logger: Logger) {
        val map: MutableMap<String, JsonObject> = LinkedHashMap()
        try {
            javaClass.getResourceAsStream("/advancements.json").use { input ->
                if (input == null) {
                    logger.error("advancements.json not found in plugin resources")
                } else {
                    val arr: JsonArray =
                        JsonParser.parseReader(InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray()
                    for (el in arr) {
                        val obj: JsonObject = el.getAsJsonObject()
                        map.put(obj.get("id").getAsString(), obj)
                    }
                    logger.info("Loaded {} advancement definitions from registry", map.size)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load advancements.json", e)
        }
        this.advancements = Collections.unmodifiableMap(map)
    }

    fun getAll(): MutableMap<String, JsonObject> {
        return advancements
    }

    fun getTotal(): Int {
        return advancements.size
    }

    fun getTotalForCategory(category: String?): Int {
        val prefix: String = "minecraft:" + category + "/"
        return advancements.keys.count { it.startsWith(prefix) }
    }

    fun isValidCategory(category: String?): Boolean {
        return category != null && VALID_CATEGORIES.contains(category)
    }

    companion object {
        private val VALID_CATEGORIES: Set<String> = setOf("story", "nether", "end", "adventure", "husbandry")
    }
}
