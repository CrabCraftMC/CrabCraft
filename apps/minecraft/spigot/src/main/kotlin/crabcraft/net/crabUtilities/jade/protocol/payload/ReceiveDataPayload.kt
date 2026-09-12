package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.JadeMessenger
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.atomic.AtomicBoolean

data class ReceiveDataPayload(private val tag: CompoundTag) : LeavesCustomPayload {
    fun tag(): CompoundTag = tag

    companion object {
        const val MAX_SIZE = 16 * 1024
        private val OVERSIZE_WARNING_LOGGED = AtomicBoolean()
        @LeavesCustomPayload.ID
        private val PACKET_RECEIVE_DATA: Identifier = JadeProtocol.id("receive_data")
        @LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<FriendlyByteBuf, ReceiveDataPayload> = StreamCodec.composite(
            ByteBufCodecs.COMPOUND_TAG, { value: ReceiveDataPayload -> value.tag() }, ::ReceiveDataPayload)

        @JvmStatic
        fun send(player: ServerPlayer, tag: CompoundTag, identity: CompoundTag) {
            val originalSize = tag.sizeInBytes()
            val response = prepareForSend(tag, identity)
            if (originalSize > MAX_SIZE && OVERSIZE_WARNING_LOGGED.compareAndSet(false, true)) {
                JadeBootstrap.LOGGER.warn("Jade response exceeded {} bytes ({}); oversized provider data was removed", MAX_SIZE, originalSize)
            }
            JadeMessenger.send(player, ReceiveDataPayload(response))
        }

        @JvmStatic
        fun prepareForSend(tag: CompoundTag, identity: CompoundTag): CompoundTag {
            if (tag.sizeInBytes() <= MAX_SIZE) return tag
            val trimmed = tag.copy()
            val protectedKeys = identity.keySet()
            var attempts = 0
            while (attempts < 10 && trimmed.sizeInBytes() > MAX_SIZE) {
                if (!removeLargest(trimmed, protectedKeys, 0, 1)) break
                attempts++
            }
            return if (trimmed.sizeInBytes() > MAX_SIZE) identity.copy() else trimmed
        }

        private fun removeLargest(tag: CompoundTag, protectedKeys: Set<String>, depth: Int, maxDepth: Int): Boolean {
            var largestSize = 0
            var largestKey: String? = null
            var largestValue: Tag? = null
            for (key in tag.keySet()) {
                if (depth == 0 && protectedKeys.contains(key)) continue
                val childTag = requireNotNull(tag.get(key))
                val size = childTag.sizeInBytes()
                if (size > largestSize) {
                    largestSize = size
                    largestKey = key
                    largestValue = childTag
                }
            }
            if (largestKey == null) return false
            if (depth < maxDepth && largestValue is CompoundTag) {
                if (!removeLargest(largestValue, protectedKeys, depth + 1, maxDepth)) tag.remove(largestKey)
            } else {
                tag.remove(largestKey)
            }
            return true
        }
    }
}
