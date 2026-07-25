package crabcraft.net.crabUtilities.recipes;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Recipe;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Unlocks every server recipe for players so the recipe book shows everything
 * and nothing has to be "discovered" through play. Applied on join and to all
 * online players when the feature starts or the config is reloaded.
 *
 * <p>Disabled by default; the {@code enabled} flag is read live so a reload
 * toggles it without re-registration. The set of recipe keys is cached and
 * rebuilt on reload (other plugins may add or remove recipes).
 *
 * <p>Ported from PaperTweaks' {@code UnlockAllRecipes} module (VanillaTweaks
 * datapack).
 */
public class UnlockAllRecipesManager implements Listener {

    private final CrabUtilities plugin;
    private volatile @Nullable Set<NamespacedKey> cachedKeys;

    public UnlockAllRecipesManager(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    private boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("tweaks.unlock-all-recipes.enabled", false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        if (this.isEnabled()) {
            this.discoverAll(event.getPlayer());
        }
    }

    /** Unlocks recipes for everyone currently online, if the feature is on. */
    public void start() {
        if (this.isEnabled()) {
            Bukkit.getOnlinePlayers().forEach(this::discoverAll);
        }
    }

    /** Rebuilds the recipe cache and re-applies to online players on reload. */
    public void refresh() {
        this.cachedKeys = null;
        this.start();
    }

    private void discoverAll(final Player player) {
        player.discoverRecipes(this.recipeKeys());
    }

    private Set<NamespacedKey> recipeKeys() {
        Set<NamespacedKey> keys = this.cachedKeys;
        if (keys == null) {
            keys = new HashSet<>();
            final Iterator<Recipe> iterator = Bukkit.recipeIterator();
            while (iterator.hasNext()) {
                if (iterator.next() instanceof final Keyed keyed) {
                    keys.add(keyed.getKey());
                }
            }
            this.cachedKeys = keys;
        }
        return keys;
    }
}
