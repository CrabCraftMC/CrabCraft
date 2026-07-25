package crabcraft.net.crabUtilities.jade.protocol.payload;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import crabcraft.net.crabUtilities.jade.protocol.JadeMessenger;
import crabcraft.net.crabUtilities.jade.protocol.payload.LeavesCustomPayload;
import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public record ReceiveDataPayload(CompoundTag tag) implements LeavesCustomPayload {

    public static final int MAX_SIZE = 16 * 1024;
    private static final AtomicBoolean OVERSIZE_WARNING_LOGGED = new AtomicBoolean();

    @ID
    private static final Identifier PACKET_RECEIVE_DATA = JadeProtocol.id("receive_data");

    @Codec
    private static final StreamCodec<FriendlyByteBuf, ReceiveDataPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.COMPOUND_TAG, ReceiveDataPayload::tag, ReceiveDataPayload::new
    );

    public static void send(ServerPlayer player, CompoundTag tag, CompoundTag identity) {
        int originalSize = tag.sizeInBytes();
        CompoundTag response = prepareForSend(tag, identity);
        if (originalSize > MAX_SIZE && OVERSIZE_WARNING_LOGGED.compareAndSet(false, true)) {
            JadeBootstrap.LOGGER.warn(
                    "Jade response exceeded {} bytes ({}); oversized provider data was removed",
                    MAX_SIZE, originalSize);
        }
        JadeMessenger.send(player, new ReceiveDataPayload(response));
    }

    static CompoundTag prepareForSend(CompoundTag tag, CompoundTag identity) {
        if (tag.sizeInBytes() <= MAX_SIZE) {
            return tag;
        }

        CompoundTag trimmed = tag.copy();
        Set<String> protectedKeys = identity.keySet();
        for (int attempts = 0; attempts < 10 && trimmed.sizeInBytes() > MAX_SIZE; attempts++) {
            if (!removeLargest(trimmed, protectedKeys, 0, 1)) {
                break;
            }
        }

        if (trimmed.sizeInBytes() > MAX_SIZE) {
            return identity.copy();
        }
        return trimmed;
    }

    private static boolean removeLargest(
            CompoundTag tag,
            Set<String> protectedKeys,
            int depth,
            int maxDepth
    ) {
        int largestSize = 0;
        String largestKey = null;
        Tag largestValue = null;
        for (String key : tag.keySet()) {
            if (depth == 0 && protectedKeys.contains(key)) {
                continue;
            }
            Tag childTag = Objects.requireNonNull(tag.get(key));
            int size = childTag.sizeInBytes();
            if (size > largestSize) {
                largestSize = size;
                largestKey = key;
                largestValue = childTag;
            }
        }
        if (largestKey == null) {
            return false;
        }
        if (depth < maxDepth && largestValue instanceof CompoundTag compound) {
            if (!removeLargest(compound, protectedKeys, depth + 1, maxDepth)) {
                tag.remove(largestKey);
            }
        } else {
            tag.remove(largestKey);
        }
        return true;
    }
}
