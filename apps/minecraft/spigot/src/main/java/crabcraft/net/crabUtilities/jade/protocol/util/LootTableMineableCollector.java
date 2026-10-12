package crabcraft.net.crabUtilities.jade.protocol.util;

import com.google.common.collect.Lists;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import crabcraft.net.crabUtilities.jade.protocol.WidenedFields;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.tool.ShearsToolHandler;

import java.util.List;
import java.util.function.Predicate;

public class LootTableMineableCollector {

    private final ItemStack toolItem;

    public LootTableMineableCollector(ItemStack toolItem) {
        this.toolItem = toolItem;
    }

    public static @NotNull List<Block> execute(HolderGetter<LootTable> lootRegistry, ItemStack toolItem) {
        LootTableMineableCollector collector = new LootTableMineableCollector(toolItem);
        List<Block> list = Lists.newArrayList();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!ShearsToolHandler.getInstance().test(block.defaultBlockState()).isEmpty()) {
                continue;
            }

            if (block.getLootTable().isPresent()) {
                LootTable lootTable = lootRegistry.get(block.getLootTable().get()).map(Holder::value).orElse(null);
                if (collector.doLootTable(lootTable)) {
                    list.add(block);
                }
            }
        }
        return list;
    }

    public static boolean isCorrectCondition(@NotNull LootItemCondition condition, ItemStack toolItem) {
        if (condition instanceof MatchTool matchTool) {
            return matchesItemPredicate(matchTool.predicate().orElse(null), toolItem);
        } else if (condition instanceof AnyOfCondition anyOfCondition) {
            for (Holder<LootItemCondition> child : WidenedFields.terms(anyOfCondition)) {
                if (isCorrectCondition(child.value(), toolItem)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean matchesItemPredicate(Object itemPredicate, Object toolItem) {
        if (!(itemPredicate instanceof Predicate<?> predicate)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        Predicate<Object> untypedPredicate = (Predicate<Object>) predicate;
        return untypedPredicate.test(toolItem);
    }

    boolean doLootTable(LootTable lootTable) {
        if (lootTable == null || lootTable == LootTable.EMPTY) {
            return false;
        }

        for (LootPool pool : WidenedFields.pools(lootTable)) {
            if (doLootPool(pool)) {
                return true;
            }
        }
        return false;
    }

    private boolean doLootPool(@NotNull LootPool lootPool) {
        if (WidenedFields.entries(lootPool).isEmpty()) {
            return false;
        }
        if (WidenedFields.condition(lootPool)
                .map(condition -> isCorrectCondition(condition.value(), toolItem)).orElse(false)) {
            return true;
        }
        for (LootPoolEntryContainer entry : WidenedFields.entries(lootPool)) {
            if (doLootPoolEntry(entry)) {
                return true;
            }
        }
        return false;
    }

    private boolean doLootPoolEntry(LootPoolEntryContainer entry) {
        if (entry instanceof AlternativesEntry alternativesEntry) {
            for (LootPoolEntryContainer child : WidenedFields.children(alternativesEntry)) {
                if (doLootPoolEntry(child)) {
                    return true;
                }
            }
        } else if (entry instanceof NestedLootTable nestedLootTable) {
            for (Holder<LootTable> child : WidenedFields.contents(nestedLootTable)) {
                if (doLootTable(child.value())) {
                    return true;
                }
            }
        } else {
            return WidenedFields.condition(entry)
                    .map(condition -> isCorrectCondition(condition.value(), toolItem)).orElse(false);
        }
        return false;
    }
}
