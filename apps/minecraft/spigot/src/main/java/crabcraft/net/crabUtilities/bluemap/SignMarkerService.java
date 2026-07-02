package crabcraft.net.crabUtilities.bluemap;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Player-made BlueMap markers: writing the configured keyword on the top line
 * of a sign adds a POI marker at the sign's location to the BlueMap web map;
 * the sign's remaining lines become the marker's label. Breaking the sign (or
 * editing the keyword off it) removes the marker.
 *
 * <p>Soft dependency — this class is only ever loaded after CrabUtilities has
 * confirmed the BlueMap plugin is installed, so the BlueMap API classes it
 * references are guaranteed to resolve.
 *
 * <p>BlueMap markers only live as long as the BlueMap API instance, so signs
 * are persisted to {@code bluemap-sign-markers.yml} and replayed via
 * {@link BlueMapAPI#onEnable(Consumer)}, which fires immediately if BlueMap is
 * already up and again after every BlueMap reload.
 */
public final class SignMarkerService {

    private static final String MARKER_SET_ID = "crabutilities-sign-markers";

    private final JavaPlugin plugin;
    private final SignMarkerStore store;
    private final SignMarkerListener listener;

    private final String markerSetLabel;
    private final boolean toggleable;
    private final boolean defaultHidden;
    private final String icon;
    private final Vector2i iconAnchor;

    // Live marker set per world, valid only while the BlueMap API is enabled.
    private final Map<UUID, MarkerSet> markerSets = new ConcurrentHashMap<>();

    // Kept in fields so shutdown() can unregister the exact same instances.
    private final Consumer<BlueMapAPI> onApiEnable = this::populate;
    private final Consumer<BlueMapAPI> onApiDisable = api -> markerSets.clear();

    public SignMarkerService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new SignMarkerStore(plugin);
        FileConfiguration config = plugin.getConfig();
        this.markerSetLabel = config.getString("bluemap.sign-markers.marker-set-label", "Player Markers");
        this.toggleable = config.getBoolean("bluemap.sign-markers.toggleable", true);
        this.defaultHidden = config.getBoolean("bluemap.sign-markers.default-hidden", false);
        String configuredIcon = config.getString("bluemap.sign-markers.icon", "");
        this.icon = configuredIcon == null ? "" : configuredIcon.trim();
        this.iconAnchor = new Vector2i(
                config.getInt("bluemap.sign-markers.icon-anchor-x", 0),
                config.getInt("bluemap.sign-markers.icon-anchor-y", 0));
        this.listener = new SignMarkerListener(this, config.getString("bluemap.sign-markers.keyword", "[map]"));
    }

    public String getKeyword() {
        return listener.getKeyword();
    }

    public void start() {
        store.load();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        BlueMapAPI.onEnable(onApiEnable);
        BlueMapAPI.onDisable(onApiDisable);
    }

    public void shutdown() {
        HandlerList.unregisterAll(listener);
        BlueMapAPI.unregisterListener(onApiEnable);
        BlueMapAPI.unregisterListener(onApiDisable);
        // Detach our sets so a /crabutilities reload doesn't leave stale copies
        // behind on the maps.
        BlueMapAPI.getInstance().ifPresent(api ->
                api.getMaps().forEach(map -> map.getMarkerSets().remove(MARKER_SET_ID)));
        markerSets.clear();
    }

    boolean hasMarker(Block block) {
        return store.contains(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    void addMarker(Block block, String label) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        store.put(world.getUID(), x, y, z, label);
        BlueMapAPI.getInstance().ifPresent(api -> {
            MarkerSet set = markerSetFor(api, world);
            if (set != null) {
                set.getMarkers().put(markerId(x, y, z), createMarker(label, x, y, z));
            }
        });
    }

    void removeMarker(Block block) {
        UUID worldId = block.getWorld().getUID();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (!store.remove(worldId, x, y, z)) {
            return;
        }
        MarkerSet set = markerSets.get(worldId);
        if (set != null) {
            set.getMarkers().remove(markerId(x, y, z));
        }
    }

    /**
     * Replays every stored marker into BlueMap. Runs each time the API
     * enables; BlueMap may call this off the main thread, which is fine —
     * everything touched here is thread-safe.
     */
    private void populate(BlueMapAPI api) {
        markerSets.clear();
        store.snapshot().forEach((worldId, worldMarkers) -> {
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                return;
            }
            MarkerSet set = markerSetFor(api, world);
            if (set == null) {
                return;
            }
            worldMarkers.forEach((posKey, label) -> {
                int[] pos = SignMarkerStore.parseKey(posKey);
                if (pos != null) {
                    set.getMarkers().put(markerId(pos[0], pos[1], pos[2]),
                            createMarker(label, pos[0], pos[1], pos[2]));
                }
            });
        });
    }

    /**
     * The (lazily created) marker set for a world, attached to every BlueMap
     * map rendering that world; {@code null} when BlueMap doesn't know the
     * world (e.g. no map configured for it).
     */
    private MarkerSet markerSetFor(BlueMapAPI api, World world) {
        BlueMapWorld blueMapWorld = api.getWorld(world).orElse(null);
        if (blueMapWorld == null) {
            return null;
        }
        return markerSets.computeIfAbsent(world.getUID(), id -> {
            MarkerSet set = MarkerSet.builder()
                    .label(markerSetLabel)
                    .toggleable(toggleable)
                    .defaultHidden(defaultHidden)
                    .build();
            for (BlueMapMap map : blueMapWorld.getMaps()) {
                map.getMarkerSets().put(MARKER_SET_ID, set);
            }
            return set;
        });
    }

    private POIMarker createMarker(String label, int x, int y, int z) {
        POIMarker marker = POIMarker.builder()
                .label(label)
                .position(new Vector3d(x + 0.5, y + 0.5, z + 0.5))
                .build();
        if (!icon.isEmpty()) {
            marker.setIcon(icon, iconAnchor);
        }
        return marker;
    }

    private static String markerId(int x, int y, int z) {
        return "sign_" + SignMarkerStore.key(x, y, z);
    }
}
