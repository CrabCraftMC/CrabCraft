package crabcraft.net.crabUtilities.jade.protocol.provider

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.util.ItemCollector
import crabcraft.net.crabUtilities.jade.protocol.util.ItemIterator
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import net.minecraft.world.Container
import net.minecraft.world.LockCode
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.WorldlyContainerHolder
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.vehicle.ContainerEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.EnderChestBlockEntity

enum class ItemStorageExtensionProvider : ServerExtensionProvider<ItemStack> {
    INSTANCE;

    override fun getGroups(request: Accessor<*>): List<ViewGroup<ItemStack>>? {
        val target = request.getTarget() ?: return createItemCollector(request).update(request)
        when {
            target is RandomizableContainer && target.getLootTable() != null -> return emptyList()
            target is ContainerEntity && target.getContainerLootTable() != null -> return emptyList()
            target is EnderChestBlockEntity && request.getPlayer().getEnderChestInventory().isEmpty() ->
                return emptyList()
        }
        val player = request.getPlayer()
        if (
            !player.isCreative() &&
                !player.isSpectator() &&
                target is BaseContainerBlockEntity &&
                target.lockKey !== LockCode.NO_LOCK
        )
            return emptyList()

        if (target is EnderChestBlockEntity) {
            val inventory = player.getEnderChestInventory()
            return ItemCollector(ItemIterator.ContainerItemIterator({ inventory }, 0)).update(request)
        }
        val itemCollector =
            try {
                targetCache.get(target) { createItemCollector(request) }
            } catch (e: ExecutionException) {
                JadeBootstrap.LOGGER.error("Failed to get item collector for {}", target)
                return null
            }
        return itemCollector.update(request)
    }

    override fun getUid() = UID

    override fun getDefaultPriority(): Int = 9999

    companion object {
        @JvmField
        val targetCache: Cache<Any, ItemCollector<*>> =
            CacheBuilder.newBuilder().weakKeys().expireAfterAccess(60, TimeUnit.SECONDS).build()
        private val UID = JadeProtocol.mc_id("item_storage.default")

        @JvmStatic
        fun createItemCollector(request: Accessor<*>): ItemCollector<*> {
            if (request.getTarget() is AbstractHorse) {
                return ItemCollector(ItemIterator.ContainerItemIterator({ (it as? AbstractHorse)?.inventory }, 2))
            }
            // TODO BlockEntity like fabric's ItemStorage
            val container = findContainer(request) ?: return ItemCollector.EMPTY
            if (container is ChestBlockEntity) {
                return ItemCollector(
                    ItemIterator.ContainerItemIterator(
                        { target ->
                            if (target is ChestBlockEntity) {
                                val chestBlock = target.getBlockState().getBlock()
                                val level = target.getLevel()
                                val compound =
                                    if (chestBlock is ChestBlock && level != null) {
                                        ChestBlock.getContainer(
                                            chestBlock,
                                            target.getBlockState(),
                                            level,
                                            target.getBlockPos(),
                                            true,
                                        ) // Bypass lock check
                                    } else null
                                compound ?: target
                            } else null
                        },
                        0,
                    )
                )
            }
            return ItemCollector(ItemIterator.ContainerItemIterator(0))
        }

        @JvmStatic
        fun findContainer(accessor: Accessor<*>): Container? {
            val target = accessor.getTarget()
            if (target == null && accessor is BlockAccessor) {
                val holder = accessor.getBlock() as? WorldlyContainerHolder
                if (holder != null)
                    return holder.getContainer(accessor.getBlockState(), accessor.getLevel(), accessor.getPosition())
            }
            return target as? Container
        }
    }
}
