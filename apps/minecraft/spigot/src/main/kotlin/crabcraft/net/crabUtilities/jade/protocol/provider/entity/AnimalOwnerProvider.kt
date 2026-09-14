package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.NicknameComponentResolver
import io.papermc.paper.adventure.PaperAdventure
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.Services
import net.minecraft.server.players.NameAndId
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.OwnableEntity
import org.bukkit.Bukkit
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import java.util.UUID

enum class AnimalOwnerProvider : StreamServerDataProvider<EntityAccessor, Component> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Component? {
        val level = accessor.getLevel()
        val uuid = getOwnerUUID(accessor.getEntity())
        val nickname = lookupNickname(uuid)
        if (nickname != null) {
            return nickname
        }
        val entity = uuid?.let(level::getEntity)
        if (entity != null) {
            return entity.name
        }
        val name = lookupPlayerName(uuid, level.server.services()) ?: return null
        return Component.literal(name)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Component> = ComponentSerialization.STREAM_CODEC

    override fun getUid(): Identifier = MC_ANIMAL_OWNER

    companion object {
        private val MC_ANIMAL_OWNER = JadeProtocol.mc_id("animal_owner")

        @JvmStatic
        fun getOwnerUUID(entity: Entity?): UUID? {
            if (entity is OwnableEntity) {
                val reference = entity.ownerReference
                if (reference != null) {
                    return reference.getUUID()
                }
            }
            return null
        }

        private fun lookupNickname(uuid: UUID?): Component? {
            val essentials = Bukkit.getPluginManager().getPlugin("Essentials")
            val nickname = NicknameComponentResolver.forUniqueId(essentials, uuid)
            return if (nickname == null) null else PaperAdventure.asVanilla(nickname)
        }

        @JvmStatic
        fun lookupPlayerName(uuid: UUID?, services: Services): String? {
            if (uuid == null) {
                return null
            }
            return services.nameToIdCache().get(uuid).map(NameAndId::name).orElse(null)
        }
    }
}
