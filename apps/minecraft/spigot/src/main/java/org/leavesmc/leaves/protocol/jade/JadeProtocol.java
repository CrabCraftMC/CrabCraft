package org.leavesmc.leaves.protocol.jade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.jadepaper.JadeMessenger;
import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.jade.accessor.BlockAccessor;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.payload.ClientHandshakePayload;
import org.leavesmc.leaves.protocol.jade.payload.ReceiveDataPayload;
import org.leavesmc.leaves.protocol.jade.payload.RequestBlockPayload;
import org.leavesmc.leaves.protocol.jade.payload.RequestEntityPayload;
import org.leavesmc.leaves.protocol.jade.payload.ServerHandshakePayload;
import org.leavesmc.leaves.protocol.jade.provider.ItemStorageExtensionProvider;
import org.leavesmc.leaves.protocol.jade.provider.ItemStorageProvider;
import org.leavesmc.leaves.protocol.jade.provider.JadeProvider;
import org.leavesmc.leaves.protocol.jade.provider.ServerDataProvider;
import org.leavesmc.leaves.protocol.jade.provider.ServerExtensionProvider;
import org.leavesmc.leaves.protocol.jade.provider.block.*;
import org.leavesmc.leaves.protocol.jade.provider.entity.AnimalOwnerProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.EntityHealthProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.MobBreedingProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.MobGrowthProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.NextEntityDropProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.PetArmorProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.StatusEffectsProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.WaxedProvider;
import org.leavesmc.leaves.protocol.jade.provider.entity.ZombieVillagerProvider;
import org.leavesmc.leaves.protocol.jade.util.CommonUtil;
import org.leavesmc.leaves.protocol.jade.util.HierarchyLookup;
import org.leavesmc.leaves.protocol.jade.util.LootTableMineableCollector;
import org.leavesmc.leaves.protocol.jade.util.PairHierarchyLookup;
import org.leavesmc.leaves.protocol.jade.util.PriorityStore;
import org.leavesmc.leaves.protocol.jade.util.WrappedHierarchyLookup;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JadeProtocol {

    public static final String PROTOCOL_ID = "jade";
    public static final String PROTOCOL_VERSION = "9";
    public static final HierarchyLookup<ServerDataProvider<EntityAccessor>> entityDataProviders = new HierarchyLookup<>(Entity.class);
    public static final PairHierarchyLookup<ServerDataProvider<BlockAccessor>> blockDataProviders = new PairHierarchyLookup<>(new HierarchyLookup<>(Block.class), new HierarchyLookup<>(BlockEntity.class));
    public static final WrappedHierarchyLookup<ServerExtensionProvider<ItemStack>> itemStorageProviders = WrappedHierarchyLookup.forAccessor();
    private static final Set<ServerPlayer> enabledPlayers = ConcurrentHashMap.newKeySet();
    private static final double REQUEST_MARGIN = 1.0D;

    public static PriorityStore<Identifier, JadeProvider> priorities;
    private static List<Block> shearableBlocks = null;

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PROTOCOL_ID, path);
    }

    @Contract("_ -> new")
    public static @NotNull Identifier mc_id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    public static void init() {
        priorities = new PriorityStore<>(JadeProvider::getDefaultPriority, JadeProvider::getUid);

        // core plugin
        blockDataProviders.register(BlockEntity.class, BlockNameProvider.INSTANCE);

        // universal plugin
        entityDataProviders.register(Entity.class, ItemStorageProvider.getEntity());
        blockDataProviders.register(Block.class, ItemStorageProvider.getBlock());

        itemStorageProviders.register(Object.class, ItemStorageExtensionProvider.INSTANCE);
        itemStorageProviders.register(Block.class, ItemStorageExtensionProvider.INSTANCE);

        // vanilla plugin
        entityDataProviders.register(Entity.class, AnimalOwnerProvider.INSTANCE);
        entityDataProviders.register(LivingEntity.class, StatusEffectsProvider.INSTANCE);
        entityDataProviders.register(LivingEntity.class, EntityHealthProvider.INSTANCE);
        entityDataProviders.register(CopperGolem.class, WaxedProvider.INSTANCE);
        entityDataProviders.register(AgeableMob.class, MobGrowthProvider.INSTANCE);
        entityDataProviders.register(Tadpole.class, MobGrowthProvider.INSTANCE);
        entityDataProviders.register(Animal.class, MobBreedingProvider.INSTANCE);
        entityDataProviders.register(Allay.class, MobBreedingProvider.INSTANCE);
        entityDataProviders.register(Mob.class, PetArmorProvider.INSTANCE);

        entityDataProviders.register(Chicken.class, NextEntityDropProvider.INSTANCE);
        entityDataProviders.register(Armadillo.class, NextEntityDropProvider.INSTANCE);
        entityDataProviders.register(Sniffer.class, NextEntityDropProvider.INSTANCE);

        entityDataProviders.register(ZombieVillager.class, ZombieVillagerProvider.INSTANCE);

        blockDataProviders.register(BrewingStandBlockEntity.class, BrewingStandProvider.INSTANCE);
        blockDataProviders.register(BeehiveBlockEntity.class, BeehiveProvider.INSTANCE);
        blockDataProviders.register(CommandBlockEntity.class, CommandBlockProvider.INSTANCE);
        blockDataProviders.register(JukeboxBlockEntity.class, JukeboxProvider.INSTANCE);
        blockDataProviders.register(LecternBlockEntity.class, LecternProvider.INSTANCE);

        blockDataProviders.register(ComparatorBlockEntity.class, RedstoneProvider.INSTANCE);
        blockDataProviders.register(HopperBlockEntity.class, HopperLockProvider.INSTANCE);
        blockDataProviders.register(CalibratedSculkSensorBlockEntity.class, RedstoneProvider.INSTANCE);

        blockDataProviders.register(AbstractFurnaceBlockEntity.class, FurnaceProvider.INSTANCE);
        blockDataProviders.register(ChiseledBookShelfBlockEntity.class, ChiseledBookshelfProvider.INSTANCE);
        blockDataProviders.register(TrialSpawnerBlockEntity.class, MobSpawnerCooldownProvider.INSTANCE);

        itemStorageProviders.register(CampfireBlock.class, CampfireProvider.INSTANCE);

        blockDataProviders.idMapped();
        entityDataProviders.idMapped();

        blockDataProviders.loadComplete(priorities);
        entityDataProviders.loadComplete(priorities);
        itemStorageProviders.loadComplete(priorities);

        rebuildShearableBlocks();
    }

    public static void clientHandshake(ServerPlayer player, ClientHandshakePayload payload) {
        if (!payload.protocolVersion().equals(PROTOCOL_VERSION)) {
            player.sendSystemMessage(Component.literal("You are using a different version of Jade than the server. Please update Jade or report to the server operator").withColor(0xff0000));
            return;
        }
        JadeMessenger.send(player, new ServerHandshakePayload(Collections.emptyMap(), shearableBlocks, blockDataProviders.mappedIds(), entityDataProviders.mappedIds()));
        enabledPlayers.add(player);
    }

    public static void resendHandshake(ServerPlayer player) {
        JadeMessenger.send(player, new ServerHandshakePayload(Collections.emptyMap(), shearableBlocks, blockDataProviders.mappedIds(), entityDataProviders.mappedIds()));
        enabledPlayers.add(player);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        enabledPlayers.remove(player);
    }

    public static void requestEntityData(ServerPlayer player, RequestEntityPayload payload) {
        if (!enabledPlayers.contains(player)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(JadeBootstrap.INSTANCE, (task) -> {
            int requestedId = payload.data().id();
            CompoundTag identity = new CompoundTag();
            identity.putInt("EntityId", requestedId);
            CompoundTag tag = payload.data().data().copy();
            tag.putInt("EntityId", requestedId);

            try {
                Entity requested = CommonUtil.wrapPartEntityParent(
                        CommonUtil.getPartEntity(player.level().getEntity(requestedId), payload.data().partIndex()));
                if (!isValidEntityTarget(player, requested)) {
                    return;
                }

                EntityAccessor accessor = payload.data().unpack(player);
                Entity entity = accessor.getEntity();
                if (!isValidEntityTarget(player, entity)) {
                    return;
                }

                identity.putInt("EntityId", entity.getId());
                tag.putInt("EntityId", entity.getId());
                List<ServerDataProvider<EntityAccessor>> providers = entityDataProviders.get(entity);
                for (ServerDataProvider<EntityAccessor> provider : providers) {
                    if (!payload.dataProviders().contains(provider)) {
                        continue;
                    }
                    try {
                        provider.appendServerData(tag, accessor);
                    } catch (Exception e) {
                        JadeBootstrap.LOGGER.warn("Error while saving data for entity {}", entity);
                    }
                }
            } catch (Exception e) {
                JadeBootstrap.LOGGER.warn("Error while collecting Jade data for entity {}", requestedId, e);
            } finally {
                ReceiveDataPayload.send(player, tag, identity);
            }
        });
    }

    public static void requestBlockData(ServerPlayer player, RequestBlockPayload payload) {
        if (!enabledPlayers.contains(player)) {
            return;
        }
        BlockHitResult hit = payload.data().hit();
        BlockPos pos = hit.getBlockPos();
        CompoundTag identity = createBlockIdentity(pos);
        CompoundTag tag = payload.data().data().copy();
        tag.remove("BlockId");
        org.jadepaper.JadeNbtUtils.writeBlockPosToTag(pos, tag);

        try {
            if (!isValidBlockTarget(player, hit)) {
                return;
            }

            BlockAccessor accessor = payload.data().unpack(player);
            Block block = accessor.getBlock();
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            identity.putString("BlockId", blockId);
            tag.putString("BlockId", blockId);

            BlockEntity blockEntity = accessor.getBlockEntity();
            List<ServerDataProvider<BlockAccessor>> providers;
            if (blockEntity != null) {
                providers = blockDataProviders.getMerged(block, blockEntity);
            } else {
                providers = blockDataProviders.first.get(block);
            }

            for (ServerDataProvider<BlockAccessor> provider : providers) {
                if (!payload.dataProviders().contains(provider)) {
                    continue;
                }
                try {
                    provider.appendServerData(tag, accessor);
                } catch (Exception e) {
                    JadeBootstrap.LOGGER.warn("Error while saving data for block {}", accessor.getBlockState());
                }
            }
        } catch (Exception e) {
            JadeBootstrap.LOGGER.warn("Error while collecting Jade data for block at {}", pos, e);
        } finally {
            ReceiveDataPayload.send(player, tag, identity);
        }
    }

    public static void onServerReload() {
        rebuildShearableBlocks();
        for (ServerPlayer player : enabledPlayers) {
            JadeMessenger.send(player, new ServerHandshakePayload(Collections.emptyMap(), shearableBlocks, blockDataProviders.mappedIds(), entityDataProviders.mappedIds()));
        }
    }

    private static void rebuildShearableBlocks() {
        try {
            shearableBlocks = Collections.unmodifiableList(LootTableMineableCollector.execute(
                MinecraftServer.getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE),
                Items.SHEARS.getDefaultInstance()
            ));
        } catch (Throwable t) {
            shearableBlocks = List.of();
            JadeBootstrap.LOGGER.error("Failed to collect shearable blocks", t);
        }
    }

    private static boolean isValidEntityTarget(ServerPlayer player, Entity entity) {
        if (entity == null || entity.level() != player.level() || !entity.isAlive()) {
            return false;
        }
        double maxDistance = Mth.square(player.entityInteractionRange() + REQUEST_MARGIN);
        return player.distanceToSqr(entity) <= maxDistance && player.hasLineOfSight(entity);
    }

    private static CompoundTag createBlockIdentity(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        org.jadepaper.JadeNbtUtils.writeBlockPosToTag(pos, tag);
        return tag;
    }

    private static boolean isValidBlockTarget(ServerPlayer player, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (!player.level().isLoaded(pos)) {
            return false;
        }
        double maxReach = player.blockInteractionRange() + REQUEST_MARGIN;
        if (pos.distSqr(player.blockPosition()) > Mth.square(maxReach)) {
            return false;
        }
        Vec3 eyePosition = player.getEyePosition();
        Vec3 direction = hit.getLocation().subtract(eyePosition);
        if (direction.lengthSqr() < 1.0E-6D) {
            return false;
        }
        BlockHitResult serverHit = player.level().clip(new ClipContext(
                eyePosition,
                eyePosition.add(direction.normalize().scale(maxReach)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
        return serverHit.getType() == HitResult.Type.BLOCK && serverHit.getBlockPos().equals(pos);
    }

}
