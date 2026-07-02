package crabcraft.net.crabUtilities.bluemap;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/**
 * Watches signs for the marker keyword and keeps {@link SignMarkerService} in
 * sync when marker signs are placed, edited, or destroyed.
 */
final class SignMarkerListener implements Listener {

    static final String PERMISSION = "crabutilities.bluemap.marker";
    static final String DEFAULT_KEYWORD = "[map]";

    private final SignMarkerService service;
    private final String keyword;

    SignMarkerListener(SignMarkerService service, String keyword) {
        this.service = service;
        this.keyword = keyword == null || keyword.isBlank() ? DEFAULT_KEYWORD : keyword.trim();
    }

    String getKeyword() {
        return keyword;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        // Only the front face carries the keyword; ignore back-side edits so
        // they can't wipe a marker created on the front.
        if (event.getSide() != Side.FRONT) {
            return;
        }
        Block block = event.getBlock();
        String firstLine = plain(event.line(0)).trim();
        if (!firstLine.equalsIgnoreCase(keyword)) {
            // Editing the keyword off an existing marker sign removes the marker.
            if (service.hasMarker(block)) {
                service.removeMarker(block);
                event.getPlayer().sendMessage(Component.text("Map marker removed.", NamedTextColor.YELLOW));
            }
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Component.text("You don't have permission to create map markers.", NamedTextColor.RED));
            return;
        }

        StringBuilder label = new StringBuilder();
        List<Component> lines = event.lines();
        for (int i = 1; i < lines.size(); i++) {
            String line = plain(lines.get(i)).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(line);
        }
        String markerLabel = label.isEmpty() ? "Marker" : label.toString();

        service.addMarker(block, markerLabel);
        // Recolor the keyword line as confirmation the marker was created.
        event.line(0, Component.text(keyword, NamedTextColor.AQUA));
        player.sendMessage(Component.text("Map marker \"" + markerLabel + "\" added to BlueMap.", NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        removeIfMarker(event.getBlock());
    }

    // Catches signs the server destroys itself, e.g. a wall sign popping off
    // when its supporting block is removed.
    @EventHandler(ignoreCancelled = true)
    public void onBlockDestroy(BlockDestroyEvent event) {
        removeIfMarker(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::removeIfMarker);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::removeIfMarker);
    }

    private void removeIfMarker(Block block) {
        if (service.hasMarker(block)) {
            service.removeMarker(block);
        }
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
