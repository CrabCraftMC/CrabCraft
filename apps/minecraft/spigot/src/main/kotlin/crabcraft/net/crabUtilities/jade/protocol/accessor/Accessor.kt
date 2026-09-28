package crabcraft.net.crabUtilities.jade.protocol.accessor

import io.netty.buffer.Unpooled
import java.util.function.Supplier
import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.Tag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.HitResult
import org.apache.commons.lang3.ArrayUtils

abstract class Accessor<T : HitResult>(
    private val level: ServerLevel,
    private val player: Player,
    private val hit: Supplier<T>,
) {
    protected var verify = false
    private var buffer: RegistryFriendlyByteBuf? = null

    open fun getLevel() = level

    open fun getPlayer() = player

    private fun buffer(): RegistryFriendlyByteBuf {
        val result = buffer ?: RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()).also { buffer = it }
        result.clear()
        return result
    }

    open fun <D : Any> encodeAsNbt(streamCodec: StreamEncoder<RegistryFriendlyByteBuf, D>, value: D): Tag {
        val buffer = buffer()
        streamCodec.encode(buffer, value)
        val tag = ByteArrayTag(ArrayUtils.subarray(buffer.array(), 0, buffer.readableBytes()))
        buffer.clear()
        return tag
    }

    open fun getHitResult(): T = hit.get()

    abstract fun getTarget(): Any?
}
