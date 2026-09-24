package crabcraft.net.crabUtilities.jade.protocol.accessor

import com.google.common.base.Suppliers
import java.util.function.Supplier
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

open class BlockAccessor
private constructor(
    level: ServerLevel,
    player: Player,
    hit: BlockHitResult,
    private val blockState: BlockState,
    private val blockEntity: Supplier<BlockEntity?>?,
) : Accessor<BlockHitResult>(level, player, Supplier { hit }) {
    open fun getBlock() = blockState.block

    open fun getBlockState() = blockState

    open fun getBlockEntity(): BlockEntity? = blockEntity?.get()

    open fun getPosition() = getHitResult().blockPos

    override fun getTarget(): Any? = getBlockEntity()

    data class SyncData(
        val showDetails: Boolean,
        val hit: BlockHitResult,
        val serversideRep: ItemStack,
        val data: CompoundTag,
    ) {
        fun showDetails() = showDetails

        fun hit() = hit

        fun serversideRep() = serversideRep

        fun data() = data

        fun unpack(player: ServerPlayer): BlockAccessor {
            val blockState = player.level().getBlockState(hit.blockPos)
            val blockEntity =
                if (blockState.hasBlockEntity()) {
                    Suppliers.memoize<BlockEntity?> { player.level().getBlockEntity(hit.blockPos) }
                } else null
            return BlockAccessor(player.level(), player, hit, blockState, blockEntity)
        }

        companion object {
            @JvmField
            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncData> =
                StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    { value: SyncData -> value.showDetails },
                    StreamCodec.of<FriendlyByteBuf, BlockHitResult>(
                        { buf, hit -> buf.writeBlockHitResult(hit) },
                        { it.readBlockHitResult() },
                    ),
                    { value: SyncData -> value.hit },
                    ItemStack.OPTIONAL_STREAM_CODEC,
                    { value: SyncData -> value.serversideRep },
                    ByteBufCodecs.COMPOUND_TAG,
                    { value: SyncData -> value.data },
                    ::SyncData,
                )
        }
    }
}
