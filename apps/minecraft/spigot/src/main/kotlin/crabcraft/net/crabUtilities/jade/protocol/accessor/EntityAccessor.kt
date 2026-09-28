package crabcraft.net.crabUtilities.jade.protocol.accessor

import com.google.common.base.Suppliers
import crabcraft.net.crabUtilities.jade.protocol.util.CommonUtil
import java.util.function.Supplier
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

open class EntityAccessor
private constructor(
    level: ServerLevel,
    player: Player,
    hit: Supplier<EntityHitResult>,
    private val entity: Supplier<Entity?>,
) : Accessor<EntityHitResult>(level, player, hit) {
    open fun getEntity(): Entity? = CommonUtil.wrapPartEntityParent(getRawEntity())

    open fun getRawEntity(): Entity? = entity.get()

    override fun getTarget(): Any? = getEntity()

    data class SyncData(
        val showDetails: Boolean,
        val id: Int,
        val partIndex: Int,
        val hitVec: Vec3,
        val data: CompoundTag,
    ) {
        fun showDetails() = showDetails

        fun id() = id

        fun partIndex() = partIndex

        fun hitVec() = hitVec

        fun data() = data

        fun unpack(player: ServerPlayer): EntityAccessor {
            val entity =
                Suppliers.memoize<Entity?> { CommonUtil.getPartEntity(player.level().getEntity(id), partIndex) }
            return EntityAccessor(
                player.level(),
                player,
                Suppliers.memoize { EntityHitResult(entity.get()!!, hitVec) },
                entity,
            )
        }

        companion object {
            @JvmField
            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncData> =
                StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    { value: SyncData -> value.showDetails },
                    ByteBufCodecs.VAR_INT,
                    { value: SyncData -> value.id },
                    ByteBufCodecs.VAR_INT,
                    { value: SyncData -> value.partIndex },
                    ByteBufCodecs.VECTOR3F.map(::Vec3, Vec3::toVector3f),
                    { value: SyncData -> value.hitVec },
                    ByteBufCodecs.COMPOUND_TAG,
                    { value: SyncData -> value.data },
                    ::SyncData,
                )
        }
    }
}
