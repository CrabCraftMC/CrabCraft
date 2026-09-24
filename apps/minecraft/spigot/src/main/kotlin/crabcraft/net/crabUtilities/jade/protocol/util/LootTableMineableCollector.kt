package crabcraft.net.crabUtilities.jade.protocol.util

import crabcraft.net.crabUtilities.jade.protocol.WidenedFields
import java.util.function.Predicate
import net.minecraft.core.HolderGetter
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer
import net.minecraft.world.level.storage.loot.entries.NestedLootTable
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.MatchTool

open class LootTableMineableCollector(
    private val lootRegistry: HolderGetter<LootTable>,
    private val toolItem: ItemStack,
) {
    private fun doLootTable(lootTable: LootTable?): Boolean =
        lootTable != null && lootTable !== LootTable.EMPTY && WidenedFields.pools(lootTable).any { doLootPool(it) }

    private fun doLootPool(lootPool: LootPool): Boolean = WidenedFields.entries(lootPool).any { doLootPoolEntry(it) }

    private fun doLootPoolEntry(entry: LootPoolEntryContainer): Boolean =
        when (entry) {
            is AlternativesEntry -> WidenedFields.children(entry).any { doLootPoolEntry(it) }
            is NestedLootTable ->
                doLootTable(
                    WidenedFields.contents(entry)
                        .map(
                            { lootRegistry.get(it).map { holder -> holder.value() }.orElse(null) },
                            { it },
                        )
                )
            else -> isCorrectConditions(WidenedFields.conditions(entry), toolItem)
        }

    companion object {
        @JvmStatic
        fun execute(lootRegistry: HolderGetter<LootTable>, toolItem: ItemStack): List<Block> {
            val collector = LootTableMineableCollector(lootRegistry, toolItem)
            val result = ArrayList<Block>()
            val shears = Items.SHEARS.defaultInstance
            val shearsTool = shears.get(DataComponents.TOOL)
            for (block in BuiltInRegistries.BLOCK) {
                val state = block.defaultBlockState()
                if (
                    shears.isCorrectToolForDrops(state) ||
                        shearsTool != null && shearsTool.getMiningSpeed(state) > shearsTool.defaultMiningSpeed()
                )
                    continue
                val lootTable = block.lootTable
                if (lootTable.isPresent) {
                    val table = lootRegistry.get(lootTable.get()).map { it.value() }.orElse(null)
                    if (collector.doLootTable(table)) result.add(block)
                }
            }
            return result
        }

        @JvmStatic
        fun isCorrectConditions(conditions: List<LootItemCondition>, toolItem: ItemStack): Boolean {
            if (conditions.size != 1) return false
            return when (val condition = conditions.first()) {
                is MatchTool -> matchesItemPredicate(condition.predicate().orElse(null), toolItem)
                is AnyOfCondition -> WidenedFields.terms(condition).any { isCorrectConditions(listOf(it), toolItem) }
                else -> false
            }
        }

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun matchesItemPredicate(itemPredicate: Any?, toolItem: Any?): Boolean =
            (itemPredicate as? Predicate<Any?>)?.test(toolItem) ?: false
    }
}
