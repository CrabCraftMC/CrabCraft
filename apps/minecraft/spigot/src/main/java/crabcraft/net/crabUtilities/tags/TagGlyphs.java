package crabcraft.net.crabUtilities.tags;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;

/** Keeps the optional Nexo classes outside the service and placeholder class graph. */
@FunctionalInterface
interface TagGlyphs {
    Component preview(String glyphId);

    static TagGlyphs create(JavaPlugin plugin) {
        var nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        if (nexo == null || !nexo.isEnabled()) return id -> null;
        try {
            return NexoTagGlyphBridge.create();
        } catch (LinkageError exception) {
            plugin.getLogger().warning("Nexo glyph previews are unavailable: " + exception.getMessage());
            return id -> null;
        }
    }
}
