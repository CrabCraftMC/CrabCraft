package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.player.PlayerTradeEvent;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.MerchantRecipe;

/** Regression checks for Bingo #2 event correlations. */
public final class BingoCardTwoListenerRegressionTest {
    private BingoCardTwoListenerRegressionTest() {}

    public static void main(String[] args) {
        directOffhandTransitionsAreCorrelated();
        onlyTheMatchingSameTickEquipIsAttributed();
        everyArmourPieceMustHaveTheSamePlayerOwner();
        capturedFirePlacementRetainsIgnitionOwner();
        explorerMapTradesUseMapDataAcrossItemTypes();
    }

    private static void directOffhandTransitionsAreCorrelated() {
        check(
                BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "helmet", "empty", String::equals),
                "Clearing the tracked Piglin off-hand item must count as a transition");
        check(
                BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "helmet", "chestplate", String::equals),
                "Replacing equipped armour with the next pickup in the same tick must count as a transition");
        check(
                !BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "helmet", "helmet", String::equals),
                "Keeping the tracked item in the Piglin off hand must not count as a transition");
        check(
                !BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "chestplate", "empty", String::equals),
                "An unrelated previous off-hand item must not count as the tracked transition");
    }

    private static void onlyTheMatchingSameTickEquipIsAttributed() {
        check(
                BingoCardTwoListener.canAttributePiglinArmour(true, true, 40, 40, true, true),
                "A fresh matching equip following the tracked off-hand transition must be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, false, 40, 40, true, true),
                "Pre-equipped armour without an observed pickup must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, true, 39, 40, true, true),
                "An equip outside the tracked transition tick must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(false, true, 40, 40, true, true),
                "An expired player pickup must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, true, 40, 40, false, true),
                "An item equipped into an unrelated slot must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, true, 40, 40, true, false),
                "An unrelated equipped item must not be attributed");
    }

    private static void everyArmourPieceMustHaveTheSamePlayerOwner() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        EnumMap<EquipmentSlot, UUID> owners = new EnumMap<>(EquipmentSlot.class);
        owners.put(EquipmentSlot.HEAD, first);
        owners.put(EquipmentSlot.CHEST, first);
        owners.put(EquipmentSlot.LEGS, first);
        owners.put(EquipmentSlot.FEET, first);
        check(
                BingoCardTwoListener.allArmourSlotsOwnedBy(owners, first),
                "One player dropping all four pieces must satisfy ownership");

        owners.put(EquipmentSlot.FEET, second);
        check(
                !BingoCardTwoListener.allArmourSlotsOwnedBy(owners, first),
                "Armour dropped by another player must prevent cross-player attribution");

        owners.remove(EquipmentSlot.FEET);
        check(
                !BingoCardTwoListener.allArmourSlotsOwnedBy(owners, first),
                "A pre-equipped unowned armour piece must not satisfy ownership");
    }

    private static void capturedFirePlacementRetainsIgnitionOwner() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        check(
                BingoCardTwoListener.shouldRetainFireOwnerAfterPlaceEvent(
                        true, player, player),
                "Paper's captured fire BlockPlaceEvent must not erase the preceding ignition owner");
        check(
                !BingoCardTwoListener.shouldRetainFireOwnerAfterPlaceEvent(
                        true, player, otherPlayer),
                "A fire placement by another player must clear stale ownership");
        check(
                !BingoCardTwoListener.shouldRetainFireOwnerAfterPlaceEvent(
                        false, player, player),
                "A non-fire block placement must clear stale fire ownership");
    }

    private static void explorerMapTradesUseMapDataAcrossItemTypes() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Item explorerMap = BuiltInRegistries.ITEM.getOptional(
                Identifier.withDefaultNamespace("woodland_mansion_map")).orElseThrow();
        for (Item item : new Item[]{Items.FILLED_MAP, explorerMap, Items.COMPASS, Items.EMERALD}) {
            // Standalone tests do not load item components from data packs.
            item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        }

        check(completedMapTrades(Items.FILLED_MAP, true, true, true, Villager.Profession.CARTOGRAPHER) == 1,
                "A decorated filled map trade must still complete the explorer-map task");
        check(completedMapTrades(explorerMap, true, true, true, Villager.Profession.CARTOGRAPHER) == 1,
                "The dedicated 26.3 explorer-map item must complete the explorer-map task");
        check(completedMapTrades(Items.FILLED_MAP, true, false, true, Villager.Profession.CARTOGRAPHER) == 0,
                "An ordinary filled map without an exploration marker must not complete the task");
        check(completedMapTrades(explorerMap, false, true, true, Villager.Profession.CARTOGRAPHER) == 0,
                "An explorer map without generated map data must not complete the task");
        check(completedMapTrades(explorerMap, true, true, false, Villager.Profession.CARTOGRAPHER) == 0,
                "A map trade without a compass ingredient must not complete the task");
        check(completedMapTrades(explorerMap, true, true, true, Villager.Profession.LIBRARIAN) == 0,
                "A map traded by another profession must not complete the task");
    }

    private static int completedMapTrades(
            Item item, boolean hasMapId, boolean hasMarker, boolean costsCompass,
            Villager.Profession profession) {
        net.minecraft.world.item.ItemStack result = new net.minecraft.world.item.ItemStack(item);
        if (hasMapId) result.set(DataComponents.MAP_ID, new MapId(1));
        if (hasMarker) {
            result.set(DataComponents.MAP_DECORATIONS, new MapDecorations(Map.of(
                    "+", new MapDecorations.Entry(MapDecorationTypes.WOODLAND_MANSION, 0, 0, 180))));
        }
        MerchantRecipe recipe = new MerchantRecipe(CraftItemStack.asBukkitCopy(result), 10);
        recipe.addIngredient(CraftItemStack.asBukkitCopy(new net.minecraft.world.item.ItemStack(
                costsCompass ? Items.COMPASS : Items.EMERALD)));
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);
        Villager villager = (Villager) Proxy.newProxyInstance(Villager.class.getClassLoader(),
                new Class<?>[]{Villager.class}, (proxy, method, args) ->
                        method.getName().equals("getProfession") ? profession : null);
        AtomicInteger completed = new AtomicInteger();
        BingoCardTwoListener listener = new BingoCardTwoListener(null,
                (candidate, task) -> candidate == player && task == BingoTask.EXPLORER_MAP_TRADE,
                (candidate, task) -> completed.incrementAndGet());
        listener.onPlayerTrade(new PlayerTradeEvent(player, villager, recipe, true, true));
        return completed.get();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
