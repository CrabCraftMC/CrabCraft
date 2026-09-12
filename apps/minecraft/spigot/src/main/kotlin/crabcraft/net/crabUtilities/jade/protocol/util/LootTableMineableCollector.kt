package crabcraft.net.crabUtilities.jade.protocol.util

import crabcraft.net.crabUtilities.jade.protocol.WidenedFields
import crabcraft.net.crabUtilities.jade.protocol.tool.ShearsToolHandler
import net.minecraft.core.HolderGetter
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer
import net.minecraft.world.level.storage.loot.entries.NestedLootTable
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.MatchTool
import java.util.function.Predicate

open class LootTableMineableCollector(
    private val lootRegistry: HolderGetter<LootTable>,
    private val toolItem: ItemStack
) {
    private fun doLootTable(lootTable: LootTable?): Boolean {
        if (lootTable == null || lootTable == LootTable.EMPTY) return false
        for (pool in WidenedFields.pools(lootTable)) {
            if (doLootPool(pool)) return true
        }
        return false
    }

    private fun doLootPool(lootPool: LootPool): Boolean {
        for (entry in WidenedFields.entries(lootPool)) {
            if (doLootPoolEntry(entry)) return true
        }
        return false
    }

    private fun doLootPoolEntry(entry: LootPoolEntryContainer): Boolean {
        when (entry) {
            is AlternativesEntry -> {
                for (child in WidenedFields.children(entry)) {
                    if (doLootPoolEntry(child)) return true
                }
            }
            is NestedLootTable -> {
                val lootTable = WidenedFields.contents(entry).map(
                    { key -> lootRegistry.get(key).map { it.value() }.orElse(null) },
                    { it }
                )
                return doLootTable(lootTable)
            }
            else -> return isCorrectConditions(WidenedFields.conditions(entry), toolItem)
        }
        return false
    }

    companion object {
        @JvmStatic
        fun execute(lootRegistry: HolderGetter<LootTable>, toolItem: ItemStack): List<Block> {
            val collector = LootTableMineableCollector(lootRegistry, toolItem)
            val list = ArrayList<Block>()
            for (block in BuiltInRegistries.BLOCK) {
                if (!ShearsToolHandler.getInstance().test(block.defaultBlockState()).isEmpty) continue
                if (block.lootTable.isPresent) {
                    val lootTable = lootRegistry.get(block.lootTable.get()).map { it.value() }.orElse(null)
                    if (collector.doLootTable(lootTable)) list.add(block)
                }
            }
            return list
        }

        @JvmStatic
        fun isCorrectConditions(conditions: List<LootItemCondition>, toolItem: ItemStack): Boolean {
            if (conditions.size != 1) return false
            when (val condition = conditions.first()) {
                is MatchTool -> return matchesItemPredicate(condition.predicate().orElse(null), toolItem)
                is AnyOfCondition -> {
                    for (child in WidenedFields.terms(condition)) {
                        if (isCorrectConditions(listOf(child), toolItem)) return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun matchesItemPredicate(itemPredicate: Any?, toolItem: Any?): Boolean {
            if (itemPredicate !is Predicate<*>) return false
            return (itemPredicate as Predicate<Any?>).test(toolItem)
        }
    }
}
