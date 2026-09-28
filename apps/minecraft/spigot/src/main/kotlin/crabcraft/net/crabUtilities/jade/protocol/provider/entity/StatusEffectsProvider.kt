package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity

enum class StatusEffectsProvider : StreamServerDataProvider<EntityAccessor, List<MobEffectInstance>> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): List<MobEffectInstance>? {
        val effects =
            (accessor.getEntity() as LivingEntity)
                .getActiveEffects()
                .stream()
                .filter(MobEffectInstance::isVisible)
                .toList()
        return effects.takeUnless { it.isEmpty() }
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, List<MobEffectInstance>> = STREAM_CODEC

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("potion_effects")
        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, List<MobEffectInstance>> =
            ByteBufCodecs.list<RegistryFriendlyByteBuf, MobEffectInstance>().apply(MobEffectInstance.STREAM_CODEC)
    }
}
