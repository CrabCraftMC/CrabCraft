package crabcraft.net.crabUtilities.netherportals;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stores custom portal membership and reconstructs it without vanilla's 21-block cap. */
final class CustomPortalRegistry {

    private static final int MAX_PORTAL_BLOCKS = 256 * 256;
    private static final List<BlockFace> FRAME_NEIGHBOURS = List.of(
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    );

    private final Map<UUID, Map<Long, CustomPortalBounds>> portalsByWorld = new HashMap<>();

    void register(final World world, final PortalAxis axis, final Collection<Block> portalBlocks) {
        final List<CustomPortalBounds.BlockPosition> positions = portalBlocks.stream()
                .map(CustomPortalRegistry::positionOf)
                .toList();
        this.register(world, new CustomPortalBounds(axis, positions));
    }

    Optional<CustomPortalBounds> findOrDiscover(final Block seed) {
        final @Nullable PortalAxis axis = axisOf(seed);
        if (axis == null) {
            return Optional.empty();
        }

        final Map<Long, CustomPortalBounds> portals = this.portalsByWorld.get(seed.getWorld().getUID());
        final CustomPortalBounds cached = portals == null ? null : portals.get(blockKey(seed.getX(), seed.getY(), seed.getZ()));
        if (cached != null && cached.axis() == axis && cached.contains(positionOf(seed))) {
            return Optional.of(cached);
        }

        return this.discover(seed, axis).map(bounds -> {
            this.register(seed.getWorld(), bounds);
            return bounds;
        });
    }

    List<CustomPortalBounds> findPortalsTouchingFrame(final Block frame) {
        final List<CustomPortalBounds> found = new ArrayList<>();
        for (final BlockFace face : FRAME_NEIGHBOURS) {
            final Block adjacent = frame.getRelative(face);
            final @Nullable PortalAxis axis = axisOf(adjacent);
            if (axis == null || !axis.isInPlane(face)) {
                continue;
            }

            final CustomPortalBounds.BlockPosition adjacentPosition = positionOf(adjacent);
            if (found.stream().anyMatch(bounds -> bounds.contains(adjacentPosition))) {
                continue;
            }

            this.discover(adjacent, axis).ifPresent(bounds -> {
                this.register(frame.getWorld(), bounds);
                found.add(bounds);
            });
        }
        return List.copyOf(found);
    }

    void unregister(final World world, final CustomPortalBounds bounds) {
        final Map<Long, CustomPortalBounds> portals = this.portalsByWorld.get(world.getUID());
        if (portals == null) {
            return;
        }
        remove(portals, bounds);
        if (portals.isEmpty()) {
            this.portalsByWorld.remove(world.getUID());
        }
    }

    void forget(final World world, final CustomPortalBounds.BlockPosition block) {
        final Map<Long, CustomPortalBounds> portals = this.portalsByWorld.get(world.getUID());
        if (portals == null) {
            return;
        }
        portals.remove(blockKey(block.x(), block.y(), block.z()));
        if (portals.isEmpty()) {
            this.portalsByWorld.remove(world.getUID());
        }
    }

    static @Nullable PortalAxis axisOf(final Block block) {
        if (block.getType() != Material.NETHER_PORTAL) {
            return null;
        }
        final BlockData data = block.getBlockData();
        if (!(data instanceof final Orientable orientable)) {
            return null;
        }
        return PortalAxis.from(orientable.getAxis());
    }

    private Optional<CustomPortalBounds> discover(final Block seed, final PortalAxis axis) {
        final Deque<Block> pending = new ArrayDeque<>();
        final Set<CustomPortalBounds.BlockPosition> checked = new HashSet<>();
        final List<CustomPortalBounds.BlockPosition> portalBlocks = new ArrayList<>();
        pending.add(seed);

        while (!pending.isEmpty()) {
            final Block block = pending.removeFirst();
            final CustomPortalBounds.BlockPosition position = positionOf(block);
            if (!checked.add(position) || axisOf(block) != axis) {
                continue;
            }

            portalBlocks.add(position);
            if (portalBlocks.size() > MAX_PORTAL_BLOCKS) {
                return Optional.empty();
            }

            pending.addLast(block.getRelative(BlockFace.UP));
            pending.addLast(block.getRelative(BlockFace.DOWN));
            pending.addLast(block.getRelative(axis.left));
            pending.addLast(block.getRelative(axis.right));
        }

        if (portalBlocks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CustomPortalBounds(axis, portalBlocks));
    }

    private void register(final World world, final CustomPortalBounds bounds) {
        final Map<Long, CustomPortalBounds> portals = this.portalsByWorld.computeIfAbsent(
                world.getUID(), ignored -> new HashMap<>());
        final Set<CustomPortalBounds> replaced = new HashSet<>();
        for (final CustomPortalBounds.BlockPosition block : bounds.blocks()) {
            final CustomPortalBounds previous = portals.get(blockKey(block.x(), block.y(), block.z()));
            if (previous != null && previous != bounds) {
                replaced.add(previous);
            }
        }
        replaced.forEach(previous -> remove(portals, previous));
        bounds.blocks().forEach(block -> portals.put(blockKey(block.x(), block.y(), block.z()), bounds));
    }

    private static void remove(final Map<Long, CustomPortalBounds> portals, final CustomPortalBounds bounds) {
        bounds.blocks().forEach(block -> portals.remove(blockKey(block.x(), block.y(), block.z()), bounds));
    }

    private static CustomPortalBounds.BlockPosition positionOf(final Block block) {
        return new CustomPortalBounds.BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private static long blockKey(final int x, final int y, final int z) {
        return ((long) x & 67108863L) << 38
                | ((long) z & 67108863L) << 12
                | ((long) y & 4095L);
    }
}
