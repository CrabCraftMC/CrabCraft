package crabcraft.net.crabUtilities.jade.protocol.util;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import crabcraft.net.crabUtilities.jade.protocol.WidenedFields;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;

import java.util.function.Predicate;
import java.util.stream.Stream;

public final class LootTableMineableCollectorRegressionTest {

    private LootTableMineableCollectorRegressionTest() {
    }

    public static void main(String[] args) {
        Object shears = new Object();
        Predicate<Object> matchesShears = candidate -> candidate == shears;

        check(LootTableMineableCollector.matchesItemPredicate(matchesShears, shears),
                "matching tool predicate was rejected");
        check(!LootTableMineableCollector.matchesItemPredicate(matchesShears, new Object()),
                "non-matching tool predicate was accepted");
        check(!LootTableMineableCollector.matchesItemPredicate(new Object(), shears),
                "non-predicate input was accepted");

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkVanillaLootStructures();
    }

    private static void checkVanillaLootStructures() {
        for (Item item : new Item[]{Items.SHEARS, Items.IRON_AXE, Items.STRING}) {
            // Standalone tests do not load item components from data packs.
            item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        }
        ItemStack shears = Items.SHEARS.getDefaultInstance();
        LootTableMineableCollector collector = new LootTableMineableCollector(shears);
        LootItemCondition.Builder matchesShears = MatchTool.toolMatches(
                ItemPredicate.Builder.item().of(BuiltInRegistries.ITEM, Items.SHEARS));
        LootItemCondition.Builder matchesAxe = MatchTool.toolMatches(
                ItemPredicate.Builder.item().of(BuiltInRegistries.ITEM, Items.IRON_AXE));

        MappedRegistry<LootItemCondition> predicates = new MappedRegistry<>(Registries.PREDICATE, Lifecycle.stable());
        Holder<LootItemCondition> referencedShears = Registry.registerForHolder(
                predicates, Identifier.fromNamespaceAndPath("test", "shears"), matchesShears.build());
        predicates.freeze();

        var matchingEntry = LootItem.lootTableItem(Items.STRING).when(matchesShears);
        LootTable matchingTable = LootTable.lootTable().withPool(LootPool.lootPool().add(matchingEntry)).build();
        check(WidenedFields.condition(matchingEntry.build()).orElseThrow().value() instanceof MatchTool,
                "loot condition accessor did not read the vanilla holder");
        check(collector.doLootTable(matchingTable), "direct tool condition was rejected");
        check(!new LootTableMineableCollector(Items.IRON_AXE.getDefaultInstance()).doLootTable(matchingTable),
                "non-matching tool condition was accepted");
        check(!collector.doLootTable(LootTable.EMPTY), "empty loot table was accepted");
        check(!collector.doLootTable(LootTable.lootTable().withPool(
                LootPool.lootPool().add(LootItem.lootTableItem(Items.STRING))).build()),
                "entry without a tool condition was accepted");

        check(collector.doLootTable(LootTable.lootTable().withPool(LootPool.lootPool()
                .add(LootItem.lootTableItem(Items.STRING).when(referencedShears))).build()),
                "registry-backed tool condition was rejected");
        check(collector.doLootTable(LootTable.lootTable().withPool(LootPool.lootPool()
                .add(LootItem.lootTableItem(Items.STRING)
                        .when(AnyOfCondition.anyOf(matchesAxe).or(referencedShears)))).build()),
                "any-of condition did not unwrap its registry-backed terms");
        check(collector.doLootTable(LootTable.lootTable().withPool(LootPool.lootPool()
                .add(LootItem.lootTableItem(Items.STRING).when(matchesAxe).otherwise(matchingEntry))).build()),
                "alternative entry did not visit the matching child");

        check(collector.doLootTable(LootTable.lootTable().withPool(LootPool.lootPool()
                .when(referencedShears).add(LootItem.lootTableItem(Items.STRING))).build()),
                "pool-level tool condition was rejected");
        check(!collector.doLootTable(LootTable.lootTable().withPool(
                LootPool.lootPool().when(referencedShears)).build()),
                "empty pool with a tool condition was accepted");

        check(collector.doLootTable(LootTable.lootTable().withPool(LootPool.lootPool()
                .add(NestedLootTable.inlineLootTable(matchingTable))).build()),
                "inline nested loot table was rejected");
        MappedRegistry<LootTable> tables = new MappedRegistry<>(Registries.LOOT_TABLE, Lifecycle.stable());
        Registry.register(tables, "test:empty", LootTable.EMPTY);
        Holder<LootTable> referencedTable = Registry.registerForHolder(
                tables, Identifier.fromNamespaceAndPath("test", "matching"), matchingTable);
        tables.freeze();
        check(collector.doLootTable(LootTable.lootTable().withPool(LootPool.lootPool()
                .add(NestedLootTable.lootTableReference(referencedTable))).build()),
                "registry-backed nested loot table was rejected");

        var ops = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(Stream.of(tables, predicates)));
        LootTable multipleNestedTables = LootTable.DIRECT_CODEC.parse(ops, JsonParser.parseString("""
                {"pools":[{"rolls":1,"entries":[
                    {"type":"minecraft:loot_table","value":["test:empty","test:matching"]}
                ]}]}
                """)).getOrThrow();
        check(collector.doLootTable(multipleNestedTables),
                "nested loot table holder set did not visit the second table");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
