package crabcraft.net.crabUtilities.tags;

import com.nexomc.nexo.NexoPlugin;
import com.nexomc.nexo.glyphs.Glyph;
import net.kyori.adventure.text.format.NamedTextColor;

/** The only tag class linking directly against Nexo. */
final class NexoTagGlyphBridge {
    private NexoTagGlyphBridge() {
    }

    static TagGlyphs create() {
        return glyphId -> {
            Glyph glyph = NexoPlugin.instance().fontManager().glyphFromID(glyphId);
            return glyph == null ? null : glyph.glyphComponent().color(NamedTextColor.WHITE);
        };
    }
}
