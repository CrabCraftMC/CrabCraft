package crabcraft.net.crabUtilities.awards;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Random;

public final class EatingAwardTrackerRegressionTest {
    public static void main(String[] args) throws Exception {
        Random random = new Random(917_643L);
        EnumMap<Material, Integer> used = new EnumMap<>(Material.class);
        used.put(Material.POTATO, 100 + random.nextInt(900));
        used.put(Material.BREAD, 100 + random.nextInt(900));
        used.put(Material.GOLDEN_CARROT, 100 + random.nextInt(900));
        int[] cake = {1 + random.nextInt(40)};
        var stored = new HashMap<Object, Object>();
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "get" -> stored.get(values[0]);
                    case "set" -> { stored.put(values[0], values[2]); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> data;
                    case "getStatistic" -> values[0] == Statistic.CAKE_SLICES_EATEN
                            ? cake[0] : used.getOrDefault((Material) values[1], 0);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        EatingAwardTracker tracker = new EatingAwardTracker();
        var initial = EatingAwardTracker.scores(player);
        check(!initial.containsKey("eat_veggie") && !initial.containsKey("eat_bread"),
                "mixed historical item uses were treated as verified meals");
        check(initial.get("eat_cookie") == 0L, "a player with no previous uses needs no cookie backfill");
        used.replaceAll((food, count) -> count + 1 + random.nextInt(50));
        check(EatingAwardTracker.scores(player).equals(initial), "later planting/composting/pot uses changed food scores");

        var cancelled = consumption(player, Material.COOKIE);
        cancelled.setCancelled(true);
        tracker.onConsume(cancelled);
        tracker.onConsume(consumption(player, Material.POTION));
        tracker.onConsume(consumption(player, Material.COD));
        check(EatingAwardTracker.scores(player).equals(initial), "cancelled meals, drinks or raw fish counted");
        for (Material food : new Material[]{Material.BREAD, Material.COOKIE, Material.BEEF, Material.COOKED_BEEF,
                Material.COOKED_COD, Material.ROTTEN_FLESH, Material.RABBIT_STEW, Material.SWEET_BERRIES}) {
            tracker.onConsume(consumption(player, food));
        }
        var replacement = consumption(player, Material.BEEF);
        replacement.setItem(item(Material.COOKIE));
        tracker.onConsume(replacement);
        var beforeImport = EatingAwardTracker.scores(player);
        check(beforeImport.get("eat_cookie") == 2L && beforeImport.get("eat_rawmeat") == 1L,
                "replacement meal type or raw meat classification was wrong");
        check(beforeImport.get("eat_fish") == 1L && beforeImport.get("eat_meat") == 2L,
                "cooked food classification was wrong");
        check(beforeImport.get("eat_soup") == 1L && beforeImport.get("eat_junkfood") == 1L
                && beforeImport.get("eat_sweet_berries") == 1L, "overlapping eating awards missed meals");

        var progress = EatingAwardTracker.decode((String) stored.get(EatingAwardTracker.DATA_KEY));
        check(progress.meals.get("eat_bread") == 1L && progress.meals.get("eat_veggie") == 4L,
                "pending historical totals prevented new meals being recorded");
        long baseline = 1 + random.nextInt(200);
        progress.historicalScores.put("eat_veggie", baseline);
        progress.historicalScores.put("eat_bread", 0L);
        stored.put(EatingAwardTracker.DATA_KEY, EatingAwardTracker.encode(progress));
        cake[0] += 2;
        check(EatingAwardTracker.scores(player).get("eat_veggie") == baseline + 6L,
                "historical import discarded meals or double-counted historical cake");
        check(EatingAwardTracker.scores(player).get("eat_bread") == 1L, "verified zero baseline discarded a new meal");
        EventHandler handler = EatingAwardTracker.class.getMethod("onConsume", PlayerItemConsumeEvent.class).getAnnotation(EventHandler.class);
        check(handler.ignoreCancelled() && handler.priority() == EventPriority.MONITOR, "consumption needs final cancellation state");

        var directory = Files.createTempDirectory("eating-award-regression-");
        var save = directory.resolve("synthetic-player.dat");
        try {
            CompoundTag playerData = new CompoundTag();
            CompoundTag values = new CompoundTag();
            values.putString(EatingAwardTracker.DATA_KEY.toString(), (String) stored.get(EatingAwardTracker.DATA_KEY));
            playerData.put("BukkitValues", values);
            NbtIo.writeCompressed(playerData, save);
            JsonObject raw = new JsonObject();
            JsonObject stats = new JsonObject();
            JsonObject custom = new JsonObject();
            custom.addProperty("minecraft:eat_cake_slice", cake[0]);
            stats.add("minecraft:custom", custom);
            raw.add("stats", stats);
            check(EatingAwardTracker.scores(save, raw).equals(EatingAwardTracker.scores(player)), "offline/restart scores differ");
            NbtIo.writeCompressed(new CompoundTag(), save);
            check(EatingAwardTracker.scores(save, raw).isEmpty(), "uninitialised offline data reset scores");
            Files.writeString(save, "synthetic malformed save");
            check(EatingAwardTracker.scores(save, raw).isEmpty(), "unreadable data reset scores");
        } finally {
            Files.deleteIfExists(save);
            Files.deleteIfExists(directory);
        }
    }

    private static ItemStack item(Material material) {
        return new ItemStack() {
            @Override public Material getType() { return material; }
            @Override public ItemStack clone() { return this; }
        };
    }

    private static PlayerItemConsumeEvent consumption(Player player, Material food) {
        return new PlayerItemConsumeEvent(player, item(food), EquipmentSlot.HAND);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
