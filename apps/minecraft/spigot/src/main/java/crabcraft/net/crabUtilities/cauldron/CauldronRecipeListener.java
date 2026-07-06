package crabcraft.net.crabUtilities.cauldron;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Cauldron crafting: dropping certain items into a water cauldron transforms
 * them in place.
 *
 * <ul>
 *   <li><b>Concrete</b> — concrete powder becomes the matching solid concrete.</li>
 *   <li><b>Mud</b> — dirt / coarse dirt / rooted dirt becomes mud.</li>
 * </ul>
 *
 * <p>Each half has its own config toggle and both are disabled by default. The
 * config is read live on every event so {@code /crabutilities reload} takes
 * effect without re-registration. The cauldron's water level is left untouched
 * (matching the upstream behaviour).
 *
 * <p>Ported from PaperTweaks' {@code CauldronConcrete} and {@code CauldronMud}
 * modules (VanillaTweaks datapacks).
 */
public class CauldronRecipeListener implements Listener {

    private static final Set<Material> DIRT_TYPES = Set.of(
            Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT);

    private final CrabUtilities plugin;

    public CauldronRecipeListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInsideBlock(final EntityInsideBlockEvent event) {
        if (event.getBlock().getType() != Material.WATER_CAULDRON) {
            return;
        }
        if (!(event.getEntity() instanceof final Item item)) {
            return;
        }

        final ItemStack stack = item.getItemStack();
        final @Nullable Material result = this.resultFor(stack.getType());
        if (result == null) {
            return;
        }

        item.getWorld().dropItem(item.getLocation(), new ItemStack(result, stack.getAmount()));
        item.remove();
    }

    private @Nullable Material resultFor(final Material type) {
        if (isConcretePowder(type) && this.plugin.getConfig().getBoolean("cauldron.concrete.enabled", false)) {
            return Material.matchMaterial(type.name().replace("_POWDER", ""));
        }
        if (DIRT_TYPES.contains(type) && this.plugin.getConfig().getBoolean("cauldron.mud.enabled", false)) {
            return Material.MUD;
        }
        return null;
    }

    private static boolean isConcretePowder(final Material type) {
        return type.name().endsWith("_CONCRETE_POWDER");
    }
}
