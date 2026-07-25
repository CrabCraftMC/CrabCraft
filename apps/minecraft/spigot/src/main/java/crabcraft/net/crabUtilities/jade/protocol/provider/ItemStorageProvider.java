package crabcraft.net.crabUtilities.jade.protocol.provider;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.LockCode;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor;
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor;
import crabcraft.net.crabUtilities.jade.protocol.util.CommonUtil;
import crabcraft.net.crabUtilities.jade.protocol.util.ItemCollector;
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ItemStorageProvider<T extends Accessor<?>> implements ServerDataProvider<T> {

    private static final StreamCodec<RegistryFriendlyByteBuf, Map.Entry<Identifier, List<ViewGroup<ItemStack>>>> NATIVE_STREAM_CODEC = ViewGroup.listCodec(ItemStack.OPTIONAL_STREAM_CODEC);

    private static final Identifier UNIVERSAL_ITEM_STORAGE = JadeProtocol.mc_id("item_storage");
    private static final AtomicBoolean VERSIONED_CODEC_AVAILABLE = new AtomicBoolean(true);
    private static final AtomicBoolean VERSIONED_CODEC_CAPABILITY_WARNING_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean VERSIONED_CODEC_RESPONSE_WARNING_LOGGED = new AtomicBoolean();

    public static ForBlock getBlock() {
        return ForBlock.INSTANCE;
    }

    public static ForEntity getEntity() {
        return ForEntity.INSTANCE;
    }

    public static void putData(CompoundTag tag, @NotNull Accessor<?> accessor) {
        Object target = accessor.getTarget();
        Player player = accessor.getPlayer();
        int clientProtocol = JadeProtocol.getClientProtocol(player);
        int serverProtocol = JadeProtocol.getServerProtocol();
        Encoding encoding = selectEncoding(
                clientProtocol,
                serverProtocol,
                JadeProtocol.isPacketEventsAvailable() && VERSIONED_CODEC_AVAILABLE.get());
        if (encoding == Encoding.UNAVAILABLE && clientProtocol == serverProtocol + 1) {
            warnVersionedCodecUnavailable(clientProtocol, null);
        }
        if (encoding != Encoding.UNAVAILABLE) {
            Map.Entry<Identifier, List<ViewGroup<ItemStack>>> entry = CommonUtil.getServerExtensionData(accessor, JadeProtocol.itemStorageProviders);
            if (entry != null) {
                List<ViewGroup<ItemStack>> groups = entry.getValue();
                for (ViewGroup<ItemStack> group : groups) {
                    if (group.views.size() > ItemCollector.MAX_SIZE) {
                        group.views = group.views.subList(0, ItemCollector.MAX_SIZE);
                    }
                }

                Tag encoded = encode(accessor, entry, encoding, clientProtocol);
                if (encoded != null) {
                    tag.put(UNIVERSAL_ITEM_STORAGE.toString(), encoded);
                    return;
                }
            }
        }
        if (target instanceof RandomizableContainer containerEntity && containerEntity.getLootTable() != null) {
            tag.putBoolean("Loot", true);
        } else if (!player.isCreative() && !player.isSpectator() && target instanceof BaseContainerBlockEntity te) {
            if (te.lockKey != LockCode.NO_LOCK) {
                tag.putBoolean("Locked", true);
            }
        }
    }

    private static Tag encode(
            Accessor<?> accessor,
            Map.Entry<Identifier, List<ViewGroup<ItemStack>>> entry,
            Encoding encoding,
            int clientProtocol) {
        if (encoding == Encoding.NATIVE) {
            return accessor.encodeAsNbt(NATIVE_STREAM_CODEC, entry);
        }
        try {
            return PacketEventsItemStackEncoder.encode(accessor, entry, clientProtocol);
        } catch (PacketEventsItemStackEncoder.UnsupportedClientProtocolException | LinkageError e) {
            VERSIONED_CODEC_AVAILABLE.set(false);
            warnVersionedCodecUnavailable(clientProtocol, e);
            return null;
        } catch (RuntimeException e) {
            if (VERSIONED_CODEC_RESPONSE_WARNING_LOGGED.compareAndSet(false, true)) {
                JadeBootstrap.LOGGER.warn(
                        "Could not encode a Jade item storage response for client protocol {}; "
                                + "this inventory response will be omitted.",
                        clientProtocol, e);
            }
            return null;
        }
    }

    private static void warnVersionedCodecUnavailable(int clientProtocol, Throwable cause) {
        if (!VERSIONED_CODEC_CAPABILITY_WARNING_LOGGED.compareAndSet(false, true)) {
            return;
        }
        String message = "Jade item storage is unavailable for client protocol " + clientProtocol
                + ". PacketEvents 2.13.0 or newer is required; inventory data will be omitted.";
        if (cause == null) {
            JadeBootstrap.LOGGER.warn(message);
        } else {
            JadeBootstrap.LOGGER.warn(message, cause);
        }
    }

    static Encoding selectEncoding(int clientProtocol, int serverProtocol, boolean packetEventsAvailable) {
        if (clientProtocol == serverProtocol) {
            return Encoding.NATIVE;
        }
        return packetEventsAvailable && clientProtocol == serverProtocol + 1
                ? Encoding.VERSIONED
                : Encoding.UNAVAILABLE;
    }

    enum Encoding {
        NATIVE,
        VERSIONED,
        UNAVAILABLE
    }

    @Override
    public Identifier getUid() {
        return UNIVERSAL_ITEM_STORAGE;
    }

    @Override
    public void appendServerData(CompoundTag tag, @NotNull T accessor) {
        if (accessor.getTarget() instanceof AbstractFurnaceBlockEntity) {
            return;
        }
        putData(tag, accessor);
    }

    @Override
    public int getDefaultPriority() {
        return 1000;
    }

    public static class ForBlock extends ItemStorageProvider<BlockAccessor> {
        private static final ForBlock INSTANCE = new ForBlock();
    }

    public static class ForEntity extends ItemStorageProvider<EntityAccessor> {
        private static final ForEntity INSTANCE = new ForEntity();
    }
}
