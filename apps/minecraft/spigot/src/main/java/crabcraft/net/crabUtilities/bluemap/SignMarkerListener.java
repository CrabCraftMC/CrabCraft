package crabcraft.net.crabUtilities.bluemap;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.List;

/**
 * Watches signs for the marker keyword and keeps {@link SignMarkerService} in
 * sync when marker signs are placed, edited, or destroyed.
 *
 * <p>Store/BlueMap mutations happen at MONITOR priority so they only commit
 * once the event's final cancellation state is known — a protection plugin
 * cancelling the edit or break at a higher priority must not leave a phantom
 * marker (or delete a real one). Only the keyword recolor runs earlier,
 * because MONITOR handlers must not modify the event.
 */
final class SignMarkerListener implements Listener {

    static final String PERMISSION = "crabutilities.bluemap.marker";

    private final SignMarkerService service;
    private final String keyword;

    SignMarkerListener(SignMarkerService service, String keyword) {
        this.service = service;
        this.keyword = keyword;
    }

    /**
     * Recolors the keyword line as confirmation. Runs at HIGHEST (not
     * MONITOR, which forbids modifying the event) — a same-tick cancellation
     * after this discards the whole edit, recolor included.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChangeRecolor(SignChangeEvent event) {
        if (event.getSide() != Side.FRONT || !isKeyword(event.line(0))) {
            return;
        }
        if (event.getPlayer().hasPermission(PERMISSION)) {
            event.line(0, Component.text(keyword, NamedTextColor.AQUA));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        // Only the front face carries the keyword; ignore back-side edits so
        // they can't wipe a marker created on the front.
        if (event.getSide() != Side.FRONT) {
            return;
        }
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (!isKeyword(event.line(0))) {
            // Editing the keyword off an existing marker sign removes the marker.
            if (service.removeMarker(block)) {
                player.sendMessage(Component.text("Map marker removed.", NamedTextColor.YELLOW));
            }
            return;
        }

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

        if (service.addMarker(block, markerLabel)) {
            player.sendMessage(Component.text("Map marker \"" + markerLabel + "\" added to BlueMap.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Map marker \"" + markerLabel
                    + "\" saved — it will show once BlueMap has a map for this world.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        removeIfMarkerSign(event.getBlock());
    }

    // Catches signs the server destroys itself, e.g. a wall sign popping off
    // when its supporting block is removed.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDestroy(BlockDestroyEvent event) {
        removeIfMarkerSign(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::removeIfMarkerSign);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::removeIfMarkerSign);
    }

    // Worlds loaded after BlueMap's API enabled were skipped by the initial
    // replay; late-populate their stored markers.
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        service.worldLoaded(event.getWorld());
    }

    private void removeIfMarkerSign(Block block) {
        // Cheap tag check first: these events fire for every block on the
        // server, and only signs can carry markers.
        if (Tag.ALL_SIGNS.isTagged(block.getType())) {
            service.removeMarker(block);
        }
    }

    private boolean isKeyword(Component line) {
        return plain(line).trim().equalsIgnoreCase(keyword);
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
