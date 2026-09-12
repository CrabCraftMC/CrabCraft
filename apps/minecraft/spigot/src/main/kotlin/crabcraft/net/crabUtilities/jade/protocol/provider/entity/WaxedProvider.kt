package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.util.Unit
import net.minecraft.world.entity.animal.golem.CopperGolem
import crabcraft.net.crabUtilities.jade.protocol.WidenedFields
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class WaxedProvider : StreamServerDataProvider<EntityAccessor, Unit> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Unit? {
        val golem = accessor.getEntity() as CopperGolem
        return if (WidenedFields.nextWeatheringTick(golem) == IGNORE_WEATHERING_TICK) Unit.INSTANCE else null
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Unit> = STREAM_CODEC

    override fun getUid(): Identifier = MC_WAXED

    companion object {
        private val MC_WAXED = JadeProtocol.mc_id("waxed")
// Mirrors CopperGolem.IGNORE_WEATHERING_TICK: a waxed golem's oxidation is suspended,
        // so its next weathering tick is parked at this sentinel instead of a real game time.
        private const val IGNORE_WEATHERING_TICK = -2L
        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, Unit> = StreamCodec.unit(Unit.INSTANCE)
    }
}
