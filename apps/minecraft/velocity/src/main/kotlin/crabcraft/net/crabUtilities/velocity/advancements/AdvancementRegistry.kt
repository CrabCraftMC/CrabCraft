package crabcraft.net.crabUtilities.velocity.advancements

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.Logger
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections

class AdvancementRegistry(logger: Logger) {
    private val advancements: Map<String, JsonObject>
    init {
        val map = LinkedHashMap<String, JsonObject>()
        try {
            javaClass.getResourceAsStream("/advancements.json").use { input ->
                if (input == null) {
                    logger.error("advancements.json not found in plugin resources")
                } else {
                    val arr = JsonParser.parseReader(InputStreamReader(input, StandardCharsets.UTF_8)).asJsonArray
                    for (el in arr) {
                        val obj = el.asJsonObject
                        map[obj.get("id").asString] = obj
                    }
                    logger.info("Loaded {} advancement definitions from registry", map.size)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load advancements.json", e)
        }
        advancements = Collections.unmodifiableMap(map)
    }
    fun getAll(): Map<String, JsonObject> = advancements
    fun getTotal(): Int = advancements.size
    fun getTotalForCategory(category: String?): Int {
        val prefix = "minecraft:" + category + "/"
        return advancements.keys.stream().filter { id -> id.startsWith(prefix) }.count().toInt()
    }
    fun isValidCategory(category: String?): Boolean = category != null && VALID_CATEGORIES.contains(category)

    companion object { private val VALID_CATEGORIES = setOf("story", "nether", "end", "adventure", "husbandry") }
}
