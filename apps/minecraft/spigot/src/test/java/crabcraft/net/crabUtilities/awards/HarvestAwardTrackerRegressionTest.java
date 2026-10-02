package crabcraft.net.crabUtilities.awards;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

public final class HarvestAwardTrackerRegressionTest {
    public static void main(String[] args) throws Exception {
        var stored = new HashMap<Object, Object>();
        PersistentDataContainer data = proxy(PersistentDataContainer.class, (method, values) -> switch (method) {
            case "get" -> stored.get(values[0]);
            case "set" -> { stored.put(values[0], values[2]); yield null; }
            default -> throw new UnsupportedOperationException(method);
        });
        Player player = proxy(Player.class, (method, values) -> {
            if (method.equals("getPersistentDataContainer")) return data;
            throw new UnsupportedOperationException(method);
        });
        HarvestAwardTracker tracker = new HarvestAwardTracker();
        check(HarvestAwardTracker.scores(player).values().stream().allMatch(count -> count == 0),
                "tracking must start at zero without inferring harvests from historical pickups");

        var shear = new PlayerShearBlockEvent(player, block(Material.BEE_NEST), item(Material.SHEARS, 1),
                EquipmentSlot.HAND, List.of(item(Material.HONEYCOMB, 3), item(Material.HONEY_BOTTLE, 9)));
        shear.setCancelled(true);
        tracker.onShear(shear);
        check(score(player, "harvest_honeycomb") == 0, "cancelled shearing counted");
        shear.setCancelled(false);
        tracker.onShear(shear);
        tracker.onShear(new PlayerShearBlockEvent(player, block(Material.BEEHIVE), item(Material.SHEARS, 1),
                EquipmentSlot.OFF_HAND, List.of(item(Material.HONEYCOMB, 2))));
        tracker.onShear(new PlayerShearBlockEvent(player, block(Material.PUMPKIN), item(Material.SHEARS, 1),
                EquipmentSlot.HAND, List.of(item(Material.HONEYCOMB, 9))));
        check(score(player, "harvest_honeycomb") == 5, "hive/nest drops or off-hand shearing miscounted");

        var cocoa = cocoaDrops(player, Material.COCOA, 2);
        cocoa.setCancelled(true);
        tracker.onBlockDrops(cocoa);
        tracker.onBlockDrops(cocoaDrops(player, Material.COCOA, 1));
        tracker.onBlockDrops(cocoaDrops(player, Material.JUNGLE_LOG, 2));
        check(score(player, "harvest_cocoa") == 0, "cancelled, immature or unrelated blocks counted");
        cocoa.setCancelled(false);
        tracker.onBlockDrops(cocoa);
        check(score(player, "harvest_cocoa") == 3, "mature cocoa must count beans, not pods or unrelated drops");

        var squid = death(EntityType.SQUID, player,
                List.of(item(Material.INK_SAC, 4), item(Material.GLOW_INK_SAC, 8)));
        squid.setCancelled(true);
        tracker.onDeath(squid);
        tracker.onDeath(death(EntityType.SQUID, null, List.of(item(Material.INK_SAC, 10))));
        tracker.onDeath(death(EntityType.ZOMBIE, player, List.of(item(Material.INK_SAC, 10))));
        check(score(player, "harvest_ink_sac") == 0, "cancelled or unattributed kills counted");
        squid.setCancelled(false);
        tracker.onDeath(squid);
        tracker.onDeath(death(EntityType.GLOW_SQUID, player,
                List.of(item(Material.GLOW_INK_SAC, 6), item(Material.INK_SAC, 10))));
        tracker.onDeath(death(EntityType.GLOW_SQUID, player, List.of()));
        check(score(player, "harvest_ink_sac") == 4 && score(player, "harvest_glow_ink_sac") == 6,
                "ink types, actual quantities or empty drops miscounted");
        check(HarvestAwardTracker.scores(player).equals(HarvestAwardTracker.scores(player)),
                "re-reading counters changed progress");

        for (var handler : java.util.Map.of("onShear", PlayerShearBlockEvent.class,
                "onBlockDrops", BlockDropItemEvent.class, "onDeath", EntityDeathEvent.class).entrySet()) {
            EventHandler annotation = HarvestAwardTracker.class.getMethod(handler.getKey(), handler.getValue())
                    .getAnnotation(EventHandler.class);
            check(annotation.ignoreCancelled() && annotation.priority() == EventPriority.MONITOR,
                    "harvest counters need final cancellation state");
        }

        var directory = Files.createTempDirectory("harvest-award-regression-");
        var save = directory.resolve("player.dat");
        try {
            check(HarvestAwardTracker.scores(save).isEmpty(), "missing offline data reset scores");
            CompoundTag playerData = new CompoundTag();
            CompoundTag values = new CompoundTag();
            HarvestAwardTracker.DATA_KEYS.values().forEach(key -> values.putLong(key.toString(), (Long) stored.get(key)));
            playerData.put("BukkitValues", values);
            NbtIo.writeCompressed(playerData, save);
            check(HarvestAwardTracker.scores(save).equals(HarvestAwardTracker.scores(player)),
                    "restart/offline scores differ from live counters");
            NbtIo.writeCompressed(new CompoundTag(), save);
            check(HarvestAwardTracker.scores(save).isEmpty(), "uninitialised offline data reset scores");
            Files.writeString(save, "malformed save");
            check(HarvestAwardTracker.scores(save).isEmpty(), "unreadable offline data reset scores");
        } finally {
            Files.deleteIfExists(save);
            Files.deleteIfExists(directory);
        }
    }

    private static long score(Player player, String award) {
        return HarvestAwardTracker.scores(player).get(award);
    }

    private static Block block(Material material) {
        return proxy(Block.class, (method, values) -> {
            if (method.equals("getType")) return material;
            throw new UnsupportedOperationException(method);
        });
    }

    private static BlockDropItemEvent cocoaDrops(Player player, Material material, int age) {
        Ageable cocoa = proxy(Ageable.class, (method, values) -> switch (method) {
            case "getAge" -> age;
            case "getMaximumAge" -> 2;
            default -> throw new UnsupportedOperationException(method);
        });
        BlockState state = proxy(BlockState.class, (method, values) -> switch (method) {
            case "getType" -> material;
            case "getBlockData" -> cocoa;
            default -> throw new UnsupportedOperationException(method);
        });
        Function<ItemStack, Item> drop = stack -> proxy(Item.class, (method, values) -> {
            if (method.equals("getItemStack")) return stack;
            throw new UnsupportedOperationException(method);
        });
        return new BlockDropItemEvent(block(Material.AIR), state, player,
                List.of(drop.apply(item(Material.COCOA_BEANS, 3)), drop.apply(item(Material.WHEAT, 20))));
    }

    private static EntityDeathEvent death(EntityType type, Player killer, List<ItemStack> drops) {
        LivingEntity entity = proxy(LivingEntity.class, (method, values) -> switch (method) {
            case "getType" -> type;
            case "getKiller" -> killer;
            default -> throw new UnsupportedOperationException(method);
        });
        return new EntityDeathEvent(entity, null, drops);
    }

    private static ItemStack item(Material material, int amount) {
        return new ItemStack() {
            @Override public Material getType() { return material; }
            @Override public int getAmount() { return amount; }
        };
    }

    private interface Invocation {
        Object call(String method, Object[] values);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, values) -> invocation.call(method.getName(), values)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
