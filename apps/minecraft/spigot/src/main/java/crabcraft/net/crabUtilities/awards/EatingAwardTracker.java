package crabcraft.net.crabUtilities.awards;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Keeps confirmed meals separate from historical totals awaiting correction. */
public final class EatingAwardTracker implements Listener {

    static final NamespacedKey DATA_KEY = new NamespacedKey("crabutilities", "eating_awards_v1");
    private static final Gson GSON = new Gson();
    static final Map<String, Set<Material>> FOODS = Map.of(
            "eat_bread", Set.of(Material.BREAD),
            "eat_cookie", Set.of(Material.COOKIE),
            "eat_fish", Set.of(Material.COOKED_COD, Material.COOKED_SALMON),
            "eat_junkfood", Set.of(Material.POISONOUS_POTATO, Material.ROTTEN_FLESH, Material.SPIDER_EYE),
            "eat_meat", Set.of(Material.COOKED_BEEF, Material.COOKED_CHICKEN, Material.COOKED_MUTTON,
                    Material.COOKED_PORKCHOP, Material.COOKED_RABBIT, Material.RABBIT_STEW),
            "eat_rawmeat", Set.of(Material.BEEF, Material.CHICKEN, Material.MUTTON, Material.PORKCHOP, Material.RABBIT),
            "eat_soup", Set.of(Material.MUSHROOM_STEW, Material.BEETROOT_SOUP, Material.RABBIT_STEW, Material.SUSPICIOUS_STEW),
            "eat_veggie", Set.of(Material.APPLE, Material.BAKED_POTATO, Material.BEETROOT, Material.BEETROOT_SOUP,
                    Material.BREAD, Material.CARROT, Material.CHORUS_FRUIT, Material.COOKIE,
                    Material.DRIED_KELP, Material.GLOW_BERRIES, Material.GOLDEN_APPLE, Material.GOLDEN_CARROT,
                    Material.MELON_SLICE, Material.MUSHROOM_STEW, Material.POTATO, Material.PUMPKIN_PIE, Material.SWEET_BERRIES),
            "eat_sweet_berries", Set.of(Material.SWEET_BERRIES));

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        progress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.isCancelled()) return;
        Material food = event.getItem().getType();
        if (FOODS.values().stream().noneMatch(foods -> foods.contains(food))) return;
        Player player = event.getPlayer();
        Progress progress = progress(player);
        FOODS.forEach((award, foods) -> {
            if (foods.contains(food)) progress.meals.merge(award, 1L, Math::addExact);
        });
        save(player, progress);
    }

    /** Called on the server thread; pending baselines are omitted, preserving old scores. */
    public static Map<String, Long> scores(Player player) {
        return progress(player).scores(player.getStatistic(Statistic.CAKE_SLICES_EATEN));
    }

    private static Progress progress(Player player) {
        String saved = player.getPersistentDataContainer().get(DATA_KEY, PersistentDataType.STRING);
        if (saved != null) return decode(saved);

        Progress progress = new Progress();
        progress.trackingStartedAt = System.currentTimeMillis();
        progress.cakeSlicesAtStart = player.getStatistic(Statistic.CAKE_SLICES_EATEN);
        FOODS.values().stream().flatMap(Set::stream).distinct().forEach(food ->
                progress.itemUsesAtStart.put(food.getKey().toString(), (long) player.getStatistic(Statistic.USE_ITEM, food)));
        FOODS.forEach((award, foods) -> {
            // Zero item uses prove no previous meals; non-zero uses do not prove eating.
            if (foods.stream().allMatch(food -> progress.itemUsesAtStart.get(food.getKey().toString()) == 0L)) {
                progress.historicalScores.put(award, award.equals("eat_veggie") ? progress.cakeSlicesAtStart : 0L);
            }
        });
        save(player, progress);
        return progress;
    }

    private static void save(Player player, Progress progress) {
        player.getPersistentDataContainer().set(DATA_KEY, PersistentDataType.STRING, encode(progress));
    }

    /** Uninitialised, missing or unreadable saves must not replace existing scores with zero. */
    public static Map<String, Long> scores(Path playerDataFile, JsonObject rawStats) {
        if (!Files.isRegularFile(playerDataFile)) return Map.of();
        try {
            var playerData = NbtIo.readCompressed(playerDataFile, NbtAccounter.defaultQuota());
            var values = playerData.getCompound("BukkitValues");
            if (values.isEmpty()) return Map.of();
            var saved = values.get().getString(DATA_KEY.toString());
            if (saved.isEmpty()) return Map.of();
            JsonObject stats = rawStats.has("stats") ? rawStats.getAsJsonObject("stats") : rawStats;
            JsonObject custom = stats.getAsJsonObject("minecraft:custom");
            long cakeSlices = custom != null && custom.has("minecraft:eat_cake_slice")
                    ? custom.get("minecraft:eat_cake_slice").getAsLong() : 0L;
            return decode(saved.get()).scores(cakeSlices);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    static String encode(Progress progress) {
        return GSON.toJson(progress);
    }

    static Progress decode(String saved) {
        Progress progress = GSON.fromJson(saved, Progress.class);
        if (progress == null || progress.trackingStartedAt <= 0 || progress.cakeSlicesAtStart < 0
                || progress.meals == null || progress.historicalScores == null || progress.itemUsesAtStart == null) {
            throw new IllegalArgumentException("Invalid eating-award progress");
        }
        for (var counts : java.util.List.of(progress.meals, progress.historicalScores, progress.itemUsesAtStart)) {
            if (counts.values().stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalArgumentException("Invalid eating-award count");
            }
        }
        return progress;
    }

    static final class Progress {
        long trackingStartedAt;
        long cakeSlicesAtStart;
        Map<String, Long> meals = new HashMap<>();
        Map<String, Long> historicalScores = new HashMap<>();
        Map<String, Long> itemUsesAtStart = new HashMap<>();

        Map<String, Long> scores(long cakeSlices) {
            Map<String, Long> scores = new HashMap<>();
            historicalScores.forEach((award, baseline) -> {
                if (!FOODS.containsKey(award)) return;
                long mealsSinceStart = meals.getOrDefault(award, 0L);
                if (award.equals("eat_veggie")) {
                    // Cake consumption does not fire PlayerItemConsumeEvent.
                    if (cakeSlices < cakeSlicesAtStart) return; // Wait for a consistent stats save.
                    mealsSinceStart = Math.addExact(mealsSinceStart, cakeSlices - cakeSlicesAtStart);
                }
                scores.put(award, Math.addExact(baseline, mealsSinceStart));
            });
            return Map.copyOf(scores);
        }
    }
}
