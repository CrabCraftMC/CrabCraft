package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack

enum class PetArmorProvider : StreamServerDataProvider<EntityAccessor, ItemStack> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): ItemStack? {
        val armour = (accessor.getEntity() as Mob).getBodyArmorItem()
        return armour.takeUnless { it.isEmpty() }
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, ItemStack> = ItemStack.OPTIONAL_STREAM_CODEC

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("pet_armor")
    }
}
