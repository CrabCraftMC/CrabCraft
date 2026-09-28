package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.JadeMessenger
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer

data class ReceiveDataPayload(val tag: CompoundTag) : LeavesCustomPayload {
    fun tag() = tag

    companion object {
        const val MAX_SIZE = 16 * 1024
        private val OVERSIZE_WARNING_LOGGED = AtomicBoolean()
        @field:LeavesCustomPayload.ID private val PACKET_RECEIVE_DATA = JadeProtocol.id("receive_data")
        @field:LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<FriendlyByteBuf, ReceiveDataPayload> =
            StreamCodec.composite(
                ByteBufCodecs.COMPOUND_TAG,
                { value: ReceiveDataPayload -> value.tag },
                ::ReceiveDataPayload,
            )

        @JvmStatic
        fun send(player: ServerPlayer, tag: CompoundTag, identity: CompoundTag) {
            val originalSize = tag.sizeInBytes()
            val response = prepareForSend(tag, identity)
            if (originalSize > MAX_SIZE && OVERSIZE_WARNING_LOGGED.compareAndSet(false, true)) {
                JadeBootstrap.LOGGER.warn(
                    "Jade response exceeded {} bytes ({}); oversized provider data was removed",
                    MAX_SIZE,
                    originalSize,
                )
            }
            JadeMessenger.send(player, ReceiveDataPayload(response))
        }

        @JvmStatic
        fun prepareForSend(tag: CompoundTag, identity: CompoundTag): CompoundTag {
            if (tag.sizeInBytes() <= MAX_SIZE) return tag
            val trimmed = tag.copy()
            val protectedKeys = identity.keySet()
            repeat(10) {
                if (trimmed.sizeInBytes() <= MAX_SIZE || !removeLargest(trimmed, protectedKeys, 0, 1)) {
                    return if (trimmed.sizeInBytes() > MAX_SIZE) identity.copy() else trimmed
                }
            }
            return if (trimmed.sizeInBytes() > MAX_SIZE) identity.copy() else trimmed
        }

        private fun removeLargest(tag: CompoundTag, protectedKeys: Set<String>, depth: Int, maxDepth: Int): Boolean {
            var largestSize = 0
            var largestKey: String? = null
            var largestValue: Tag? = null
            for (key in tag.keySet()) {
                if (depth == 0 && key in protectedKeys) continue
                val childTag = requireNotNull(tag.get(key))
                val size = childTag.sizeInBytes()
                if (size > largestSize) {
                    largestSize = size
                    largestKey = key
                    largestValue = childTag
                }
            }
            val key = largestKey ?: return false
            val value = largestValue
            if (depth < maxDepth && value is CompoundTag) {
                if (!removeLargest(value, protectedKeys, depth + 1, maxDepth)) tag.remove(key)
            } else {
                tag.remove(key)
            }
            return true
        }
    }
}
