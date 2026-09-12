package crabcraft.net.crabUtilities.awards

import com.google.gson.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.EnumMap
import java.util.Random

object EatingAwardTrackerRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val random = Random(917_643L)
        val used = EnumMap<Material, Int>(Material::class.java)
        used[Material.POTATO] = 100 + random.nextInt(900)
        used[Material.BREAD] = 100 + random.nextInt(900)
        used[Material.GOLDEN_CARROT] = 100 + random.nextInt(900)
        val cake = intArrayOf(1 + random.nextInt(40))
        val stored = HashMap<Any, Any>()
        val data = Proxy.newProxyInstance(PersistentDataContainer::class.java.classLoader,
            arrayOf(PersistentDataContainer::class.java)) { _, method, values ->
            when (method.name) {
                "get" -> stored[values!![0]]
                "set" -> { stored[values!![0]] = values[2]; null }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as PersistentDataContainer
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, values ->
            when (method.name) {
                "getPersistentDataContainer" -> data
                "getStatistic" -> if (values!![0] == Statistic.CAKE_SLICES_EATEN) cake[0] else used.getOrDefault(values[1] as Material, 0)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Player
        val tracker = EatingAwardTracker()
        val initial = EatingAwardTracker.scores(player)
        check(!initial.containsKey("eat_veggie") && !initial.containsKey("eat_bread"),
            "mixed historical item uses were treated as verified meals")
        check(initial["eat_cookie"] == 0L, "a player with no previous uses needs no cookie backfill")
        used.replaceAll { _, count -> count + 1 + random.nextInt(50) }
        check(EatingAwardTracker.scores(player) == initial, "later planting/composting/pot uses changed food scores")
        val cancelled = consumption(player, Material.COOKIE)
        cancelled.isCancelled = true
        tracker.onConsume(cancelled)
        tracker.onConsume(consumption(player, Material.POTION))
        tracker.onConsume(consumption(player, Material.COD))
        check(EatingAwardTracker.scores(player) == initial, "cancelled meals, drinks or raw fish counted")
        for (food in arrayOf(Material.BREAD, Material.COOKIE, Material.BEEF, Material.COOKED_BEEF,
            Material.COOKED_COD, Material.ROTTEN_FLESH, Material.RABBIT_STEW, Material.SWEET_BERRIES)) {
            tracker.onConsume(consumption(player, food))
        }
        val replacement = consumption(player, Material.BEEF)
        replacement.setItem(item(Material.COOKIE))
        tracker.onConsume(replacement)
        val beforeImport = EatingAwardTracker.scores(player)
        check(beforeImport["eat_cookie"] == 2L && beforeImport["eat_rawmeat"] == 1L,
            "replacement meal type or raw meat classification was wrong")
        check(beforeImport["eat_fish"] == 1L && beforeImport["eat_meat"] == 2L, "cooked food classification was wrong")
        check(beforeImport["eat_soup"] == 1L && beforeImport["eat_junkfood"] == 1L
            && beforeImport["eat_sweet_berries"] == 1L, "overlapping eating awards missed meals")
        val progress = EatingAwardTracker.decode(stored[EatingAwardTracker.DATA_KEY] as String)
        check(progress.meals["eat_bread"] == 1L && progress.meals["eat_veggie"] == 4L,
            "pending historical totals prevented new meals being recorded")
        val baseline = (1 + random.nextInt(200)).toLong()
        progress.historicalScores["eat_veggie"] = baseline
        progress.historicalScores["eat_bread"] = 0L
        stored[EatingAwardTracker.DATA_KEY] = EatingAwardTracker.encode(progress)
        cake[0] += 2
        check(EatingAwardTracker.scores(player)["eat_veggie"] == baseline + 6L,
            "historical import discarded meals or double-counted historical cake")
        check(EatingAwardTracker.scores(player)["eat_bread"] == 1L, "verified zero baseline discarded a new meal")
        val handler = EatingAwardTracker::class.java.getMethod("onConsume", PlayerItemConsumeEvent::class.java).getAnnotation(EventHandler::class.java)
        check(handler.ignoreCancelled && handler.priority == EventPriority.MONITOR, "consumption needs final cancellation state")
        val directory = Files.createTempDirectory("eating-award-regression-")
        val save = directory.resolve("synthetic-player.dat")
        try {
            val playerData = CompoundTag()
            val values = CompoundTag()
            values.putString(EatingAwardTracker.DATA_KEY.toString(), stored[EatingAwardTracker.DATA_KEY] as String)
            playerData.put("BukkitValues", values)
            NbtIo.writeCompressed(playerData, save)
            val raw = JsonObject()
            val stats = JsonObject()
            val custom = JsonObject()
            custom.addProperty("minecraft:eat_cake_slice", cake[0])
            stats.add("minecraft:custom", custom)
            raw.add("stats", stats)
            check(EatingAwardTracker.scores(save, raw) == EatingAwardTracker.scores(player), "offline/restart scores differ")
            NbtIo.writeCompressed(CompoundTag(), save)
            check(EatingAwardTracker.scores(save, raw).isEmpty(), "uninitialised offline data reset scores")
            Files.writeString(save, "synthetic malformed save")
            check(EatingAwardTracker.scores(save, raw).isEmpty(), "unreadable data reset scores")
        } finally {
            Files.deleteIfExists(save)
            Files.deleteIfExists(directory)
        }
    }

    private fun item(material: Material): ItemStack = object : ItemStack() {
        override fun getType(): Material = material
        override fun clone(): ItemStack = this
    }

    private fun consumption(player: Player, food: Material): PlayerItemConsumeEvent =
        PlayerItemConsumeEvent(player, item(food), EquipmentSlot.HAND)

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
