package crabcraft.net.crabUtilities.jade.protocol

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.allay.Allay
import net.minecraft.world.entity.animal.armadillo.Armadillo
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.frog.Tadpole
import net.minecraft.world.entity.animal.golem.CopperGolem
import net.minecraft.world.entity.animal.sniffer.Sniffer
import net.minecraft.world.entity.monster.zombie.ZombieVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import crabcraft.net.crabUtilities.jade.protocol.JadeMessenger
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.payload.ClientHandshakePayload
import crabcraft.net.crabUtilities.jade.protocol.payload.ReceiveDataPayload
import crabcraft.net.crabUtilities.jade.protocol.payload.RequestBlockPayload
import crabcraft.net.crabUtilities.jade.protocol.payload.RequestEntityPayload
import crabcraft.net.crabUtilities.jade.protocol.payload.ServerHandshakePayload
import crabcraft.net.crabUtilities.jade.protocol.provider.ItemStorageExtensionProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.ItemStorageProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerExtensionProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.block.*
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.AnimalOwnerProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.EntityHealthProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.MobBreedingProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.MobGrowthProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.NextEntityDropProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.PetArmorProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.StatusEffectsProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.WaxedProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.entity.ZombieVillagerProvider
import crabcraft.net.crabUtilities.jade.protocol.util.CommonUtil
import crabcraft.net.crabUtilities.jade.protocol.util.HierarchyLookup
import crabcraft.net.crabUtilities.jade.protocol.util.LootTableMineableCollector
import crabcraft.net.crabUtilities.jade.protocol.util.PairHierarchyLookup
import crabcraft.net.crabUtilities.jade.protocol.util.PriorityStore
import crabcraft.net.crabUtilities.jade.protocol.util.WrappedHierarchyLookup
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class JadeProtocol {
    companion object {
        const val PROTOCOL_ID = "jade"
        const val PROTOCOL_VERSION = "9"
        @JvmField var entityDataProviders = HierarchyLookup<ServerDataProvider<EntityAccessor>>(Entity::class.java)
        @JvmField var blockDataProviders = PairHierarchyLookup<ServerDataProvider<BlockAccessor>>(
            HierarchyLookup(Block::class.java), HierarchyLookup(BlockEntity::class.java))
        @JvmField var itemStorageProviders = WrappedHierarchyLookup.forAccessor<ServerExtensionProvider<ItemStack>>()
        private val enabledPlayers = ConcurrentHashMap.newKeySet<ServerPlayer>()
        private val clientProtocols = ConcurrentHashMap<UUID, Int>()
        private const val REQUEST_MARGIN = 1.0
        lateinit var priorities: PriorityStore<Identifier, JadeProvider>
        private var shearableBlocks: List<Block> = emptyList()
        @Volatile private var active = false

        @JvmStatic fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(PROTOCOL_ID, path)
        @JvmStatic fun mc_id(path: String): Identifier = Identifier.withDefaultNamespace(path)

        @JvmStatic
        @Synchronized
        fun init(inventoryDataEnabled: Boolean) {
            active = false
            enabledPlayers.clear()
            clientProtocols.clear()
            entityDataProviders = HierarchyLookup(Entity::class.java)
            blockDataProviders = PairHierarchyLookup(HierarchyLookup(Block::class.java), HierarchyLookup(BlockEntity::class.java))
            itemStorageProviders = WrappedHierarchyLookup.forAccessor()
            priorities = PriorityStore(JadeProvider::getDefaultPriority, JadeProvider::getUid)
        // core plugin
        blockDataProviders.register(BlockEntity::class.java, BlockNameProvider.INSTANCE)

        if (inventoryDataEnabled) {
            // Inventory providers read NMS containers directly. Keep them off
            // unless the deployment accepts that protection-plugin and owner
            // checks cannot currently be reproduced on this read-only path.
            entityDataProviders.register(Entity::class.java, ItemStorageProvider.getEntity())
            blockDataProviders.register(Block::class.java, ItemStorageProvider.getBlock())
            itemStorageProviders.register(Any::class.java, ItemStorageExtensionProvider.INSTANCE)
            itemStorageProviders.register(Block::class.java, ItemStorageExtensionProvider.INSTANCE)
        }

        // vanilla plugin
        entityDataProviders.register(Entity::class.java, AnimalOwnerProvider.INSTANCE)
        entityDataProviders.register(LivingEntity::class.java, StatusEffectsProvider.INSTANCE)
        entityDataProviders.register(LivingEntity::class.java, EntityHealthProvider.INSTANCE)
        entityDataProviders.register(CopperGolem::class.java, WaxedProvider.INSTANCE)
        entityDataProviders.register(AgeableMob::class.java, MobGrowthProvider.INSTANCE)
        entityDataProviders.register(Tadpole::class.java, MobGrowthProvider.INSTANCE)
        entityDataProviders.register(Animal::class.java, MobBreedingProvider.INSTANCE)
        entityDataProviders.register(Allay::class.java, MobBreedingProvider.INSTANCE)
        entityDataProviders.register(Mob::class.java, PetArmorProvider.INSTANCE)

        entityDataProviders.register(Chicken::class.java, NextEntityDropProvider.INSTANCE)
        entityDataProviders.register(Armadillo::class.java, NextEntityDropProvider.INSTANCE)
        entityDataProviders.register(Sniffer::class.java, NextEntityDropProvider.INSTANCE)

        entityDataProviders.register(ZombieVillager::class.java, ZombieVillagerProvider.INSTANCE)

        blockDataProviders.register(BrewingStandBlockEntity::class.java, BrewingStandProvider.INSTANCE)
        blockDataProviders.register(BeehiveBlockEntity::class.java, BeehiveProvider.INSTANCE)
        blockDataProviders.register(CommandBlockEntity::class.java, CommandBlockProvider.INSTANCE)
        if (inventoryDataEnabled) {
            blockDataProviders.register(JukeboxBlockEntity::class.java, JukeboxProvider.INSTANCE)
            blockDataProviders.register(LecternBlockEntity::class.java, LecternProvider.INSTANCE)
        }

        blockDataProviders.register(ComparatorBlockEntity::class.java, RedstoneProvider.INSTANCE)
        blockDataProviders.register(HopperBlockEntity::class.java, HopperLockProvider.INSTANCE)
        blockDataProviders.register(CalibratedSculkSensorBlockEntity::class.java, RedstoneProvider.INSTANCE)

        if (inventoryDataEnabled) {
            blockDataProviders.register(AbstractFurnaceBlockEntity::class.java, FurnaceProvider.INSTANCE)
            blockDataProviders.register(ChiseledBookShelfBlockEntity::class.java, ChiseledBookshelfProvider.INSTANCE)
        }
        blockDataProviders.register(TrialSpawnerBlockEntity::class.java, MobSpawnerCooldownProvider.INSTANCE)

        if (inventoryDataEnabled) {
            itemStorageProviders.register(CampfireBlock::class.java, CampfireProvider.INSTANCE)
        }


            blockDataProviders.idMapped()
            entityDataProviders.idMapped()
            blockDataProviders.loadComplete(priorities)
            entityDataProviders.loadComplete(priorities)
            itemStorageProviders.loadComplete(priorities)
            rebuildShearableBlocks()
            active = true
        }

        @JvmStatic
        @Synchronized
        fun shutdown() {
            active = false
            enabledPlayers.clear()
            clientProtocols.clear()
            shearableBlocks = emptyList()
        }

        @JvmStatic fun isActive(): Boolean = active
        @JvmStatic fun snapshotEnabledPlayers(): Set<ServerPlayer> = java.util.Set.copyOf(enabledPlayers)

        @JvmStatic
        fun clientHandshake(player: ServerPlayer, payload: ClientHandshakePayload) {
            if (!active) return
            if (payload.protocolVersion() != PROTOCOL_VERSION) {
                player.sendSystemMessage(Component.literal("You are using a different version of Jade than the server. Please update Jade or report to the server operator").withColor(0xff0000))
                return
            }
            JadeMessenger.send(player, ServerHandshakePayload(emptyMap(), shearableBlocks, blockDataProviders.mappedIds(), entityDataProviders.mappedIds()))
            enabledPlayers.add(player)
        }

        @JvmStatic
        fun resendHandshake(player: ServerPlayer) {
            if (!active) return
            JadeMessenger.send(player, ServerHandshakePayload(emptyMap(), shearableBlocks, blockDataProviders.mappedIds(), entityDataProviders.mappedIds()))
            enabledPlayers.add(player)
        }

        @JvmStatic
        fun onPlayerLeave(player: ServerPlayer) {
            enabledPlayers.remove(player)
            clientProtocols.remove(player.uuid)
        }

        @JvmStatic
        fun setClientProtocol(playerId: UUID, protocol: Int) {
            if (active) clientProtocols[playerId] = protocol
        }

        @JvmStatic
        fun getClientProtocol(player: Player): Int = clientProtocols.getOrDefault(
            player.uuid, (player.bukkitEntity as org.bukkit.entity.Player).protocolVersion)

        @JvmStatic fun getServerProtocol(): Int = SharedConstants.getProtocolVersion()
        @JvmStatic fun isPacketEventsAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("packetevents")

        @JvmStatic
        fun requestEntityData(player: ServerPlayer, payload: RequestEntityPayload) {
            if (!active || !enabledPlayers.contains(player)) return
            Bukkit.getScheduler().runTask(JadeBootstrap.INSTANCE, Runnable {
                if (!active || !enabledPlayers.contains(player)) return@Runnable
                val requestedId = payload.data().id()
                val identity = CompoundTag()
                identity.putInt("EntityId", requestedId)
                val tag = createResponseTag(identity, payload.data().data())
                try {
                    val requested = CommonUtil.wrapPartEntityParent(
                        CommonUtil.getPartEntity(player.level().getEntity(requestedId), payload.data().partIndex()))
                    if (!isValidEntityTarget(player, requested)) return@Runnable
                    val accessor = payload.data().unpack(player)
                    val entity = accessor.getEntity()
                    if (entity == null || !isValidEntityTarget(player, entity)) return@Runnable
                    identity.putInt("EntityId", entity.id)
                    tag.putInt("EntityId", entity.id)
                    val providers = entityDataProviders.get(entity)
                    for (provider in providers) {
                        if (!payload.dataProviders().contains(provider)) continue
                        try {
                            provider.appendServerData(tag, accessor)
                        } catch (e: Exception) {
                            JadeBootstrap.LOGGER.warn("Error while saving data for entity {}", entity)
                        }
                    }
                } catch (e: Exception) {
                    JadeBootstrap.LOGGER.warn("Error while collecting Jade data for entity {}", requestedId, e)
                } finally {
                    ReceiveDataPayload.send(player, tag, identity)
                }
            })
        }

        @JvmStatic
        fun requestBlockData(player: ServerPlayer, payload: RequestBlockPayload) {
            if (!active || !enabledPlayers.contains(player)) return
            val hit = payload.data().hit()
            val pos = hit.blockPos
            val identity = createBlockIdentity(pos)
            val tag = createResponseTag(identity, payload.data().data())
            try {
                if (!isValidBlockTarget(player, hit)) return
                val accessor = payload.data().unpack(player)
                val block = accessor.getBlock()
                val blockId = BuiltInRegistries.BLOCK.getKey(block).toString()
                identity.putString("BlockId", blockId)
                tag.putString("BlockId", blockId)
                val blockEntity = accessor.getBlockEntity()
                val providers = if (blockEntity != null) blockDataProviders.getMerged(block, blockEntity)
                    else blockDataProviders.first.get(block)
                for (provider in providers) {
                    if (!payload.dataProviders().contains(provider)) continue
                    try {
                        provider.appendServerData(tag, accessor)
                    } catch (e: Exception) {
                        JadeBootstrap.LOGGER.warn("Error while saving data for block {}", accessor.getBlockState())
                    }
                }
            } catch (e: Exception) {
                JadeBootstrap.LOGGER.warn("Error while collecting Jade data for block at {}", pos, e)
            } finally {
                ReceiveDataPayload.send(player, tag, identity)
            }
        }

        @JvmStatic
        fun onServerReload() {
            if (!active) return
            rebuildShearableBlocks()
            for (player in enabledPlayers) {
                JadeMessenger.send(player, ServerHandshakePayload(emptyMap(), shearableBlocks, blockDataProviders.mappedIds(), entityDataProviders.mappedIds()))
            }
        }

        private fun rebuildShearableBlocks() {
            val server: MinecraftServer? = MinecraftServer.getServer()
            if (server == null) {
                shearableBlocks = emptyList()
                return
            }
            try {
                shearableBlocks = Collections.unmodifiableList(LootTableMineableCollector.execute(
                    server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE),
                    Items.SHEARS.defaultInstance))
            } catch (t: Throwable) {
                shearableBlocks = emptyList()
                JadeBootstrap.LOGGER.error("Failed to collect shearable blocks", t)
            }
        }

        private fun isValidEntityTarget(player: ServerPlayer, entity: Entity?): Boolean {
            if (entity == null || entity.level() !== player.level() || !entity.isAlive) return false
            val maxDistance = Mth.square(player.entityInteractionRange() + REQUEST_MARGIN)
            return player.distanceToSqr(entity) <= maxDistance && player.hasLineOfSight(entity)
        }

        private fun createBlockIdentity(pos: BlockPos): CompoundTag {
            val tag = CompoundTag()
            JadeNbtUtils.writeBlockPosToTag(pos, tag)
            return tag
        }

        /** Starts a response with trusted identity plus Jade's allowlisted client request controls. */
        @JvmStatic
        fun createResponseTag(identity: CompoundTag, requestData: CompoundTag): CompoundTag {
            val response = identity.copy()
            if (requestData.getBooleanOr("SortItems", false)) response.putBoolean("SortItems", true)
            return response
        }

        private fun isValidBlockTarget(player: ServerPlayer, hit: BlockHitResult): Boolean {
            val pos = hit.blockPos
            if (!player.level().isLoaded(pos)) return false
            val maxReach = player.blockInteractionRange() + REQUEST_MARGIN
            if (pos.distSqr(player.blockPosition()) > Mth.square(maxReach)) return false
            val eyePosition = player.eyePosition
            val direction = hit.location.subtract(eyePosition)
            if (direction.lengthSqr() < 1.0E-6) return false
            val serverHit = player.level().clip(ClipContext(
                eyePosition, eyePosition.add(direction.normalize().scale(maxReach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            return serverHit.type == HitResult.Type.BLOCK && serverHit.blockPos == pos
        }
    }
}
