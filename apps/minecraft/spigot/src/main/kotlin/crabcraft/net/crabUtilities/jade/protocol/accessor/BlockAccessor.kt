package crabcraft.net.crabUtilities.jade.protocol.accessor

import com.google.common.base.Suppliers
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import java.util.function.Supplier

/** Information about a block target and its context. */
class BlockAccessor private constructor(builder: Builder) : Accessor<BlockHitResult>(
    builder.level, builder.player, Suppliers.ofInstance(builder.hit)
) {
    private val blockState = builder.blockState
    private val blockEntity = builder.blockEntity

    fun getBlock(): Block = getBlockState().block
    fun getBlockState(): BlockState = blockState
    fun getBlockEntity(): BlockEntity? = blockEntity?.get()
    fun getPosition(): BlockPos = getHitResult().blockPos
    override fun getTarget(): Any? = getBlockEntity()

    open class Builder {
        lateinit var level: ServerLevel
        lateinit var player: Player
        lateinit var hit: BlockHitResult
        @JvmField var blockState: BlockState = Blocks.AIR.defaultBlockState()
        @JvmField var blockEntity: Supplier<BlockEntity?>? = null

        open fun level(level: ServerLevel): Builder { this.level = level; return this }
        open fun player(player: Player): Builder { this.player = player; return this }
        open fun hit(hit: BlockHitResult): Builder { this.hit = hit; return this }
        open fun blockState(blockState: BlockState): Builder { this.blockState = blockState; return this }
        open fun blockEntity(blockEntity: Supplier<BlockEntity?>?): Builder { this.blockEntity = blockEntity; return this }

        open fun from(accessor: BlockAccessor): Builder {
            level = accessor.getLevel()
            player = accessor.getPlayer()
            hit = accessor.getHitResult()
            blockEntity = Supplier(accessor::getBlockEntity)
            blockState = accessor.getBlockState()
            return this
        }

        open fun build(): BlockAccessor = BlockAccessor(this)
    }

    data class SyncData(private val showDetails: Boolean, private val hit: BlockHitResult, private val serversideRep: ItemStack, private val data: CompoundTag) {
        fun showDetails(): Boolean = showDetails
        fun hit(): BlockHitResult = hit
        fun serversideRep(): ItemStack = serversideRep
        fun data(): CompoundTag = data

        fun unpack(player: ServerPlayer): BlockAccessor {
            var blockEntity: Supplier<BlockEntity?>? = null
            val blockState = player.level().getBlockState(hit.blockPos)
            if (blockState.hasBlockEntity()) {
                blockEntity = Suppliers.memoize { player.level().getBlockEntity(hit.blockPos) }
            }
            return Builder().level(player.level()).player(player).hit(hit).blockState(blockState).blockEntity(blockEntity).build()
        }

        companion object {
            @JvmField val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncData> = StreamCodec.composite(
                ByteBufCodecs.BOOL, { value: SyncData -> value.showDetails() },
                StreamCodec.of(FriendlyByteBuf::writeBlockHitResult, FriendlyByteBuf::readBlockHitResult), { value: SyncData -> value.hit() },
                ItemStack.OPTIONAL_STREAM_CODEC, { value: SyncData -> value.serversideRep() },
                ByteBufCodecs.COMPOUND_TAG, { value: SyncData -> value.data() },
                ::SyncData)
        }
    }
}
