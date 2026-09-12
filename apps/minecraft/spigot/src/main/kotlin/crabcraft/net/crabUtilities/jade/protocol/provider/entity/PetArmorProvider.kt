package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class PetArmorProvider : StreamServerDataProvider<EntityAccessor, ItemStack> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): ItemStack? {
        val armor = (accessor.getEntity() as Mob).bodyArmorItem
        return if (armor.isEmpty) null else armor
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, ItemStack> = ItemStack.OPTIONAL_STREAM_CODEC

    override fun getUid(): Identifier = MC_PET_ARMOR

    companion object {
        private val MC_PET_ARMOR = JadeProtocol.mc_id("pet_armor")
    }
}
