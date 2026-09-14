package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class StatusEffectsProvider : StreamServerDataProvider<EntityAccessor, List<MobEffectInstance>> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): List<MobEffectInstance>? {
        val effects = (accessor.getEntity() as LivingEntity).activeEffects.stream().filter { it.isVisible }.toList()
        return if (effects.isEmpty()) null else effects
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, List<MobEffectInstance>> = STREAM_CODEC

    override fun getUid(): Identifier = MC_POTION_EFFECTS

    companion object {
        private val MC_POTION_EFFECTS = JadeProtocol.mc_id("potion_effects")
        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, List<MobEffectInstance>> =
            ByteBufCodecs.list<RegistryFriendlyByteBuf, MobEffectInstance>().apply(MobEffectInstance.STREAM_CODEC)
    }
}
