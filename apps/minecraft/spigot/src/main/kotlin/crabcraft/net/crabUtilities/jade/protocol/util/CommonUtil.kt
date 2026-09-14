package crabcraft.net.crabUtilities.jade.protocol.util

import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerExtensionProvider
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart

open class CommonUtil {
    companion object {
        @JvmStatic
        fun wrapPartEntityParent(target: Entity?): Entity? =
            if (target is EnderDragonPart) target.parentMob else target

        @JvmStatic
        fun getPartEntity(parent: Entity?, index: Int): Entity? {
            if (parent == null) return null
            if (index < 0) return parent
            if (parent is EnderDragon) {
                val parts = parent.getSubEntities()
                if (index < parts.size) return parts[index]
            }
            return parent
        }

        @JvmStatic
        fun <T> getServerExtensionData(
            accessor: Accessor<*>,
            lookup: WrappedHierarchyLookup<ServerExtensionProvider<T>>
        ): Map.Entry<Identifier, List<ViewGroup<T>>>? {
            for (provider in lookup.wrappedGet(accessor)) {
                val groups = try {
                    provider.getGroups(accessor)
                } catch (e: Exception) {
                    JadeBootstrap.LOGGER.error(e.toString())
                    continue
                }
                if (groups != null) return java.util.Map.entry(provider.getUid(), groups)
            }
            return null
        }
    }
}
