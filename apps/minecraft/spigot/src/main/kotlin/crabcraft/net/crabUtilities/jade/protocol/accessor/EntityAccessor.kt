package crabcraft.net.crabUtilities.jade.protocol.accessor

import com.google.common.base.Suppliers
import crabcraft.net.crabUtilities.jade.protocol.util.CommonUtil
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
import java.util.function.Supplier

open class EntityAccessor(builder: Builder) : Accessor<EntityHitResult>(builder.level, builder.player, builder.hit) {
    private val entity = builder.entity
    open fun getEntity(): Entity? = CommonUtil.wrapPartEntityParent(getRawEntity())
    open fun getRawEntity(): Entity? = entity.get()
    override fun getTarget(): Any? = getEntity()

    open class Builder {
        lateinit var level: ServerLevel
        lateinit var player: Player
        lateinit var hit: Supplier<EntityHitResult>
        lateinit var entity: Supplier<Entity?>

        open fun level(level: ServerLevel): Builder { this.level = level; return this }
        open fun player(player: Player): Builder { this.player = player; return this }
        open fun hit(hit: Supplier<EntityHitResult>): Builder { this.hit = hit; return this }
        open fun entity(entity: Supplier<Entity?>): Builder { this.entity = entity; return this }

        open fun from(accessor: EntityAccessor): Builder {
            level = accessor.getLevel()
            player = accessor.getPlayer()
            hit = Supplier(accessor::getHitResult)
            entity = Supplier(accessor::getEntity)
            return this
        }

        open fun build(): EntityAccessor = EntityAccessor(this)
    }

    data class SyncData(private val showDetails: Boolean, private val id: Int, private val partIndex: Int, private val hitVec: Vec3, private val data: CompoundTag) {
        fun showDetails(): Boolean = showDetails
        fun id(): Int = id
        fun partIndex(): Int = partIndex
        fun hitVec(): Vec3 = hitVec
        fun data(): CompoundTag = data

        fun unpack(player: ServerPlayer): EntityAccessor {
            val entity: Supplier<Entity?> = Suppliers.memoize { CommonUtil.getPartEntity(player.level().getEntity(id), partIndex) }
            return Builder().level(player.level()).player(player).entity(entity)
                .hit(Suppliers.memoize { EntityHitResult(entity.get()!!, hitVec) }).build()
        }

        companion object {
            @JvmField val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncData> = StreamCodec.composite(
                ByteBufCodecs.BOOL, { value: SyncData -> value.showDetails() },
                ByteBufCodecs.VAR_INT, { value: SyncData -> value.id() },
                ByteBufCodecs.VAR_INT, { value: SyncData -> value.partIndex() },
                ByteBufCodecs.VECTOR3F.map(::Vec3, Vec3::toVector3f), { value: SyncData -> value.hitVec() },
                ByteBufCodecs.COMPOUND_TAG, { value: SyncData -> value.data() },
                ::SyncData)
        }
    }
}
