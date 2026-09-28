package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.NicknameComponentResolver
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import io.papermc.paper.adventure.PaperAdventure
import java.util.UUID
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.Services
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.OwnableEntity
import org.bukkit.Bukkit

enum class AnimalOwnerProvider : StreamServerDataProvider<EntityAccessor, Component> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Component? {
        val level = accessor.getLevel()
        val uuid = getOwnerUUID(accessor.getEntity())
        lookupNickname(uuid)?.let {
            return it
        }
        if (uuid != null)
            level.getEntity(uuid)?.let {
                return it.getName()
            }
        val name = lookupPlayerName(uuid, level.getServer().services()) ?: return null
        return Component.literal(name)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Component> = ComponentSerialization.STREAM_CODEC

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("animal_owner")

        @JvmStatic fun getOwnerUUID(entity: Entity?): UUID? = (entity as? OwnableEntity)?.getOwnerReference()?.getUUID()

        private fun lookupNickname(uuid: UUID?): Component? {
            val essentials = Bukkit.getPluginManager().getPlugin("Essentials")
            val nickname = NicknameComponentResolver.forUniqueId(essentials, uuid) ?: return null
            return PaperAdventure.asVanilla(nickname)
        }

        @JvmStatic
        fun lookupPlayerName(uuid: UUID?, services: Services): String? {
            if (uuid == null) return null
            return services.nameToIdCache().get(uuid).map { it.name() }.orElse(null)
        }
    }
}
