package crabcraft.net.crabUtilities.jade.protocol.util;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerExtensionProvider;

import java.util.List;
import java.util.Map;

public class CommonUtil {

    public static Entity wrapPartEntityParent(Entity target) {
        if (target instanceof EnderDragonPart part) {
            return part.parentMob;
        }
        return target;
    }

    public static Entity getPartEntity(Entity parent, int index) {
        if (parent == null) {
            return null;
        }
        if (index < 0) {
            return parent;
        }
        if (parent instanceof EnderDragon dragon) {
            EnderDragonPart[] parts = dragon.getSubEntities();
            if (index < parts.length) {
                return parts[index];
            }
        }
        return parent;
    }


    public static <T> Map.Entry<Identifier, List<ViewGroup<T>>> getServerExtensionData(
        Accessor<?> accessor,
        WrappedHierarchyLookup<ServerExtensionProvider<T>> lookup) {
        for (var provider : lookup.wrappedGet(accessor)) {
            List<ViewGroup<T>> groups;
            try {
                groups = provider.getGroups(accessor);
            } catch (Exception e) {
                JadeBootstrap.LOGGER.error(e.toString());
                continue;
            }
            if (groups != null) {
                return Map.entry(provider.getUid(), groups);
            }
        }
        return null;
    }
}
