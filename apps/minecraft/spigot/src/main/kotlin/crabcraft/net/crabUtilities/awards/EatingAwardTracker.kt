package crabcraft.net.crabUtilities.awards

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataType

/** Keeps confirmed meals separate from historical totals awaiting correction. */
class EatingAwardTracker : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        progress(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (event.isCancelled) return
        val food = event.item.type
        if (FOODS.values.none { food in it }) return
        val player = event.player
        val progress = progress(player)
        FOODS.forEach { (award, foods) -> if (food in foods) progress.meals.merge(award, 1L, Math::addExact) }
        save(player, progress)
    }

    class Progress {
        @JvmField var trackingStartedAt = 0L
        @JvmField var cakeSlicesAtStart = 0L
        @JvmField var meals: MutableMap<String, Long> = HashMap()
        @JvmField var historicalScores: MutableMap<String, Long> = HashMap()
        @JvmField var itemUsesAtStart: MutableMap<String, Long> = HashMap()

        fun scores(cakeSlices: Long): Map<String, Long> {
            val scores = HashMap<String, Long>()
            for ((award, baseline) in historicalScores) {
                if (award !in FOODS) continue
                var mealsSinceStart = meals.getOrDefault(award, 0L)
                if (award == "eat_veggie") {
                    // Cake does not fire consume events; wait for a consistent saved statistic.
                    if (cakeSlices < cakeSlicesAtStart) continue
                    mealsSinceStart = Math.addExact(mealsSinceStart, cakeSlices - cakeSlicesAtStart)
                }
                scores[award] = Math.addExact(baseline, mealsSinceStart)
            }
            return java.util.Map.copyOf(scores)
        }
    }

    companion object {
        @JvmField val DATA_KEY = NamespacedKey("crabutilities", "eating_awards_v1")
        private val GSON = Gson()
        @JvmField
        val FOODS =
            mapOf(
                "eat_bread" to setOf(Material.BREAD),
                "eat_cookie" to setOf(Material.COOKIE),
                "eat_fish" to setOf(Material.COOKED_COD, Material.COOKED_SALMON),
                "eat_junkfood" to setOf(Material.POISONOUS_POTATO, Material.ROTTEN_FLESH, Material.SPIDER_EYE),
                "eat_meat" to
                    setOf(
                        Material.COOKED_BEEF,
                        Material.COOKED_CHICKEN,
                        Material.COOKED_MUTTON,
                        Material.COOKED_PORKCHOP,
                        Material.COOKED_RABBIT,
                        Material.RABBIT_STEW,
                    ),
                "eat_rawmeat" to
                    setOf(Material.BEEF, Material.CHICKEN, Material.MUTTON, Material.PORKCHOP, Material.RABBIT),
                "eat_soup" to
                    setOf(
                        Material.MUSHROOM_STEW,
                        Material.BEETROOT_SOUP,
                        Material.RABBIT_STEW,
                        Material.SUSPICIOUS_STEW,
                    ),
                "eat_veggie" to
                    setOf(
                        Material.APPLE,
                        Material.BAKED_POTATO,
                        Material.BEETROOT,
                        Material.BEETROOT_SOUP,
                        Material.BREAD,
                        Material.CARROT,
                        Material.CHORUS_FRUIT,
                        Material.COOKIE,
                        Material.DRIED_KELP,
                        Material.GLOW_BERRIES,
                        Material.GOLDEN_APPLE,
                        Material.GOLDEN_CARROT,
                        Material.MELON_SLICE,
                        Material.MUSHROOM_STEW,
                        Material.POTATO,
                        Material.PUMPKIN_PIE,
                        Material.SWEET_BERRIES,
                    ),
                "eat_sweet_berries" to setOf(Material.SWEET_BERRIES),
            )

        @JvmStatic
        fun scores(player: Player): Map<String, Long> =
            progress(player).scores(player.getStatistic(Statistic.CAKE_SLICES_EATEN).toLong())

        private fun progress(player: Player): Progress {
            val saved = player.persistentDataContainer.get(DATA_KEY, PersistentDataType.STRING)
            if (saved != null) return decode(saved)
            val progress = Progress()
            progress.trackingStartedAt = System.currentTimeMillis()
            progress.cakeSlicesAtStart = player.getStatistic(Statistic.CAKE_SLICES_EATEN).toLong()
            for (food in FOODS.values.flatten().distinct()) {
                progress.itemUsesAtStart[food.key.toString()] = player.getStatistic(Statistic.USE_ITEM, food).toLong()
            }
            FOODS.forEach { (award, foods) ->
                if (foods.all { progress.itemUsesAtStart[it.key.toString()] == 0L }) {
                    progress.historicalScores[award] = if (award == "eat_veggie") progress.cakeSlicesAtStart else 0L
                }
            }
            save(player, progress)
            return progress
        }

        private fun save(player: Player, progress: Progress) {
            player.persistentDataContainer.set(DATA_KEY, PersistentDataType.STRING, encode(progress))
        }

        /** Missing or unreadable saves must not replace existing scores with zero. */
        @JvmStatic
        fun scores(playerDataFile: Path, rawStats: JsonObject): Map<String, Long> {
            if (!Files.isRegularFile(playerDataFile)) return emptyMap()
            return try {
                val values =
                    NbtIo.readCompressed(playerDataFile, NbtAccounter.defaultQuota()).getCompound("BukkitValues")
                if (values.isEmpty) return emptyMap()
                val saved = values.get().getString(DATA_KEY.toString())
                if (saved.isEmpty) return emptyMap()
                val stats = if (rawStats.has("stats")) rawStats.getAsJsonObject("stats") else rawStats
                val custom = stats.getAsJsonObject("minecraft:custom")
                val cakeSlices =
                    if (custom != null && custom.has("minecraft:eat_cake_slice"))
                        custom.get("minecraft:eat_cake_slice").asLong
                    else 0L
                decode(saved.get()).scores(cakeSlices)
            } catch (_: IOException) {
                emptyMap()
            } catch (_: RuntimeException) {
                emptyMap()
            }
        }

        @JvmStatic fun encode(progress: Progress): String = GSON.toJson(progress)

        @JvmStatic
        fun decode(saved: String): Progress {
            val progress = GSON.fromJson(saved, Progress::class.java)
            // Gson can assign null to fields regardless of Kotlin's declared types.
            val countMaps: List<Map<String, Long?>?> =
                listOf(
                    progress?.meals,
                    progress?.historicalScores,
                    progress?.itemUsesAtStart,
                )
            require(
                progress != null &&
                    progress.trackingStartedAt > 0 &&
                    progress.cakeSlicesAtStart >= 0 &&
                    countMaps.none { it == null }
            ) {
                "Invalid eating-award progress"
            }
            for (counts in countMaps) {
                require(counts!!.values.none { it == null || it < 0 }) { "Invalid eating-award count" }
            }
            return progress
        }
    }
}
