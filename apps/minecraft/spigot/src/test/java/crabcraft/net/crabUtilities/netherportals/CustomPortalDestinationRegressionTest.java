package crabcraft.net.crabUtilities.netherportals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.world.PortalCreateEvent;

public final class CustomPortalDestinationRegressionTest {

    public static void main(String[] args) {
        normalPortalLinkedToLargePortalUsesLargePortalBottom();
        netherRoofLinkWorksInBothDirections();
        bothAxesAndNegativeCoordinatesUseCompleteBounds();
        obstructedSideFallsBackToTheClearSide();
        mountedStackUsesEntitySizedClearance();
        entityThatCannotFitKeepsPaperDestination();
        oversizedPortalsSuppressOnlyPortalPigmen();
        portalCollapseExcludesVanillaSizes();
        portalCollapseOnlyAcceptsFrameBlocksInThePortalPlane();
        overlappingCollapseRequestsAreDetectable();
        customPortalCreationPublishesPlayerAndAppliesStates();
        cancelledCustomPortalCreationAppliesNothing();
    }

    private static void normalPortalLinkedToLargePortalUsesLargePortalBottom() {
        final CustomPortalBounds normal = rectangle(PortalAxis.X, 10, 4, 70, 2, 3);
        final CustomPortalBounds large = rectangle(PortalAxis.X, 10, 4, 23, 64, 64);

        check(!normal.exceedsVanillaInteriorLimit(), "normal portals must retain Paper placement");
        check(large.exceedsVanillaInteriorLimit(), "64x64 portal was not recognized as custom-sized");
        check(large.minY() == 23 && large.height() == 64 && large.width() == 64,
                "complete 64x64 bounds were not retained");

        final FakeSafety safety = new FakeSafety(large);
        safety.supportInteriorBottom(large);
        final CustomPortalBounds.Destination destination = destination(large, 40.2, 86.0, safety);
        check(destination.y() == 23.0, "arrival should be directly above the lower frame");
        check(safety.hasSupport(destination.feetBlock()), "arrival must have solid support");
    }

    private static void netherRoofLinkWorksInBothDirections() {
        final CustomPortalBounds overworld = rectangle(PortalAxis.Z, -35, -80, 23, 64, 64);
        final CustomPortalBounds netherRoof = rectangle(PortalAxis.Z, -5, -10, 129, 2, 3);

        final FakeSafety overworldSafety = new FakeSafety(overworld);
        overworldSafety.supportInteriorBottom(overworld);
        final CustomPortalBounds.Destination fromNether = destination(overworld, -79.5, -6.1, overworldSafety);
        check(fromNether.y() == 23.0,
                "Nether-roof Y must not pin arrival to the top of the Overworld portal at Y=86");

        check(!netherRoof.exceedsVanillaInteriorLimit(),
                "large-source to normal Nether destination must keep vanilla-like placement");
    }

    private static void bothAxesAndNegativeCoordinatesUseCompleteBounds() {
        for (final PortalAxis axis : PortalAxis.values()) {
            final CustomPortalBounds portal = rectangle(axis, -90, -40, -12, 37, 28);
            final FakeSafety safety = new FakeSafety(portal);
            safety.supportInteriorBottom(portal);
            final double targetX = axis == PortalAxis.X ? -68.2 : -39.5;
            final double targetZ = axis == PortalAxis.X ? -39.5 : -68.2;
            final CustomPortalBounds.Destination destination = destination(portal, targetX, targetZ, safety);

            check(destination.y() == -12.0, axis + " portal did not use its complete lower edge");
            check(destination.feetBlock().x() < 0 && destination.feetBlock().z() < 0,
                    axis + " portal mishandled negative block coordinates");
            check(safety.isPortal(destination.feetBlock()),
                    axis + " fallback should remain in a portal block above its lower frame");
        }
    }

    private static void obstructedSideFallsBackToTheClearSide() {
        for (final PortalAxis axis : PortalAxis.values()) {
            final CustomPortalBounds portal = rectangle(axis, 5, 20, 40, 24, 30);
            final FakeSafety safety = new FakeSafety(portal);
            safety.supportAllBottomLanes(portal);
            safety.blockPositiveOutsideLane(portal);

            final double targetX = axis == PortalAxis.X ? 12.5 : 22.0;
            final double targetZ = axis == PortalAxis.X ? 22.0 : 12.5;
            final CustomPortalBounds.Destination destination = destination(portal, targetX, targetZ, safety);

            if (axis == PortalAxis.X) {
                check(destination.z() == 19.5, "X-aligned portal chose its obstructed positive-Z side");
            } else {
                check(destination.x() == 19.5, "Z-aligned portal chose its obstructed positive-X side");
            }
            check(safety.hasSupport(destination.feetBlock()), axis + " clear-side arrival was unsupported");
            check(safety.canOccupy(destination), axis + " clear-side arrival did not have enough clearance");
        }
    }

    private static void mountedStackUsesEntitySizedClearance() {
        for (final PortalAxis axis : PortalAxis.values()) {
            final CustomPortalBounds portal = rectangle(axis, 5, 20, 40, 24, 30);
            final FakeSafety safety = new FakeSafety(portal);
            safety.supportAllBottomLanes(portal);
            safety.requireClearance(4);
            safety.blockPositiveOutsideLane(portal, 2);

            final double targetX = axis == PortalAxis.X ? 12.5 : 22.0;
            final double targetZ = axis == PortalAxis.X ? 22.0 : 12.5;
            final CustomPortalBounds.Destination destination = destination(portal, targetX, targetZ, safety);

            if (axis == PortalAxis.X) {
                check(destination.z() == 19.5,
                        "X-aligned riding stack chose a side without enough vertical clearance");
            } else {
                check(destination.x() == 19.5,
                        "Z-aligned riding stack chose a side without enough vertical clearance");
            }
            check(destination.y() == 40.0, axis + " riding stack did not arrive at the portal bottom");
        }
    }

    private static void entityThatCannotFitKeepsPaperDestination() {
        final CustomPortalBounds portal = rectangle(PortalAxis.X, 0, 5, 20, 64, 64);
        final FakeSafety safety = new FakeSafety(portal);
        safety.supportAllBottomLanes(portal);
        safety.denyAllOccupancy();

        check(portal.findSafeDestination(32.5, 5.5, safety).isEmpty(),
                "an entity that cannot fit safely should retain Paper's original destination");
    }

    private static void oversizedPortalsSuppressOnlyPortalPigmen() {
        final CustomPortalBounds normal = rectangle(PortalAxis.X, 0, 5, 20, 21, 21);
        final CustomPortalBounds oversized = rectangle(PortalAxis.X, 0, 5, 20, 64, 64);

        check(CustomNetherPortalListener.shouldSuppressPortalSpawn(
                        org.bukkit.entity.EntityType.ZOMBIFIED_PIGLIN,
                        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
                        oversized),
                "oversized portals should suppress their zombified piglin spawns");
        check(!CustomNetherPortalListener.shouldSuppressPortalSpawn(
                        org.bukkit.entity.EntityType.ZOMBIFIED_PIGLIN,
                        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
                        normal),
                "vanilla-sized portals must retain zombified piglin spawning");
        check(!CustomNetherPortalListener.shouldSuppressPortalSpawn(
                        org.bukkit.entity.EntityType.ZOMBIFIED_PIGLIN,
                        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL,
                        oversized),
                "natural zombified piglin spawning must remain unchanged");
        check(!CustomNetherPortalListener.shouldSuppressPortalSpawn(
                        org.bukkit.entity.EntityType.ZOMBIE,
                        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
                        oversized),
                "other entity types must not be suppressed");
    }

    private static void portalCollapseExcludesVanillaSizes() {
        check(!rectangle(PortalAxis.X, 0, 0, 10, 21, 21).exceedsVanillaInteriorLimit(),
                "21x21 portal must remain entirely vanilla-managed when its frame breaks");
        check(rectangle(PortalAxis.X, 0, 0, 10, 22, 3).exceedsVanillaInteriorLimit(),
                "oversized width must use complete custom-portal collapse");
        check(rectangle(PortalAxis.Z, 0, 0, 10, 3, 22).exceedsVanillaInteriorLimit(),
                "oversized height must use complete custom-portal collapse");
    }

    private static void portalCollapseOnlyAcceptsFrameBlocksInThePortalPlane() {
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.UP), "upper X frame was rejected");
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.DOWN), "lower X frame was rejected");
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.EAST), "side X frame was rejected");
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.WEST), "side X frame was rejected");
        check(!PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.NORTH),
                "block outside the X portal plane could trigger collapse");
        check(!PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.SOUTH),
                "block outside the X portal plane could trigger collapse");

        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.UP), "upper Z frame was rejected");
        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.DOWN), "lower Z frame was rejected");
        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.NORTH), "side Z frame was rejected");
        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.SOUTH), "side Z frame was rejected");
        check(!PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.EAST),
                "block outside the Z portal plane could trigger collapse");
        check(!PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.WEST),
                "block outside the Z portal plane could trigger collapse");
    }

    private static void overlappingCollapseRequestsAreDetectable() {
        final CustomPortalBounds full = rectangle(PortalAxis.X, 0, 5, 10, 64, 64);
        final CustomPortalBounds remainingSubset = rectangle(PortalAxis.X, 16, 5, 10, 16, 64);
        final CustomPortalBounds separate = rectangle(PortalAxis.X, 80, 5, 10, 16, 64);

        check(full.overlaps(remainingSubset), "partially-cleared portal could be queued twice");
        check(remainingSubset.overlaps(full), "collapse overlap check must be symmetric");
        check(!full.overlaps(separate), "separate custom portals were incorrectly merged for collapse");
    }

    private static void customPortalCreationPublishesPlayerAndAppliesStates() {
        final World world = proxy(World.class);
        final Player player = proxy(Player.class);
        final AtomicInteger updates = new AtomicInteger();
        final List<BlockState> proposedStates = portalStates(16, updates);
        final AtomicReference<PortalCreateEvent> published = new AtomicReference<>();

        final boolean applied = PortalShapeFinder.fireAndApplyPortal(
                proposedStates, world, player, event -> {
                    check(updates.get() == 0,
                            "custom portal states changed before PortalCreateEvent was published");
                    published.set(event);
                });

        final PortalCreateEvent event = published.get();
        check(applied, "an accepted custom portal proposal was not applied");
        check(event != null, "custom portal creation did not publish PortalCreateEvent");
        check(event.getReason() == PortalCreateEvent.CreateReason.FIRE,
                "custom portal creation used the wrong event reason");
        check(event.getEntity() == player,
                "custom portal creation lost the igniting player");
        check(event.getBlocks().equals(proposedStates) && event.getBlocks().size() == 16,
                "custom portal creation did not publish the exact proposed states");
        check(event.getBlocks().stream().allMatch(state -> state.getType() == Material.NETHER_PORTAL),
                "custom portal event did not expose proposed Nether portal states");
        check(updates.get() == proposedStates.size(),
                "accepted custom portal creation did not apply every proposed state");
    }

    private static void cancelledCustomPortalCreationAppliesNothing() {
        final AtomicInteger updates = new AtomicInteger();
        final List<BlockState> proposedStates = portalStates(16, updates);

        final boolean applied = PortalShapeFinder.fireAndApplyPortal(
                proposedStates,
                proxy(World.class),
                proxy(Player.class),
                event -> event.setCancelled(true));

        check(!applied, "a cancelled custom portal proposal was reported as applied");
        check(updates.get() == 0,
                "a cancelled custom portal proposal changed live blocks");
    }

    private static List<BlockState> portalStates(final int count, final AtomicInteger updates) {
        final List<BlockState> states = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            states.add(proxy(BlockState.class, (proxy, method, args) -> {
                if (method.getName().equals("getType")) {
                    return Material.NETHER_PORTAL;
                }
                if (method.getName().equals("update")) {
                    check(args != null
                                    && args.length == 2
                                    && Boolean.TRUE.equals(args[0])
                                    && Boolean.FALSE.equals(args[1]),
                            "portal state updates must be forced without intermediate physics");
                    updates.incrementAndGet();
                    return true;
                }
                return defaultValue(proxy, method.getName(), method.getReturnType(), args);
            }));
        }
        return states;
    }

    private static <T> T proxy(final Class<T> type) {
        return proxy(type, (proxy, method, args) ->
                defaultValue(proxy, method.getName(), method.getReturnType(), args));
    }

    private static <T> T proxy(final Class<T> type, final java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(
            final Object proxy,
            final String methodName,
            final Class<?> returnType,
            final Object[] args
    ) {
        if (methodName.equals("equals")) return proxy == args[0];
        if (methodName.equals("hashCode")) return System.identityHashCode(proxy);
        if (methodName.equals("toString")) return "test-proxy";
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return '\0';
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0F;
        if (returnType == double.class) return 0.0D;
        return null;
    }

    private static CustomPortalBounds rectangle(
            final PortalAxis axis,
            final int flatStart,
            final int plane,
            final int bottomY,
            final int width,
            final int height
    ) {
        final List<CustomPortalBounds.BlockPosition> blocks = new ArrayList<>(width * height);
        for (int flat = flatStart; flat < flatStart + width; flat++) {
            for (int y = bottomY; y < bottomY + height; y++) {
                blocks.add(axis == PortalAxis.X
                        ? new CustomPortalBounds.BlockPosition(flat, y, plane)
                        : new CustomPortalBounds.BlockPosition(plane, y, flat));
            }
        }
        return new CustomPortalBounds(axis, blocks);
    }

    private static CustomPortalBounds.Destination destination(
            final CustomPortalBounds portal,
            final double targetX,
            final double targetZ,
            final FakeSafety safety
    ) {
        return portal.findSafeDestination(targetX, targetZ, safety)
                .orElseThrow(() -> new AssertionError("no safe destination found"));
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeSafety implements CustomPortalBounds.Safety {
        private final Set<CustomPortalBounds.BlockPosition> portalBlocks;
        private final Set<CustomPortalBounds.BlockPosition> supportedFeet = new HashSet<>();
        private final Set<CustomPortalBounds.BlockPosition> obstructed = new HashSet<>();
        private int requiredClearance = 2;
        private boolean denyAllOccupancy;

        private FakeSafety(final CustomPortalBounds portal) {
            this.portalBlocks = portal.blocks();
        }

        private void supportInteriorBottom(final CustomPortalBounds portal) {
            portal.blocks().stream()
                    .filter(block -> block.y() == portal.minY())
                    .forEach(this.supportedFeet::add);
        }

        private void supportAllBottomLanes(final CustomPortalBounds portal) {
            portal.blocks().stream()
                    .filter(block -> block.y() == portal.minY())
                    .forEach(block -> {
                        this.supportedFeet.add(block);
                        if (portal.axis() == PortalAxis.X) {
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() - 1));
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() + 1));
                        } else {
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x() - 1, block.y(), block.z()));
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x() + 1, block.y(), block.z()));
                        }
                    });
        }

        private void blockPositiveOutsideLane(final CustomPortalBounds portal) {
            this.blockPositiveOutsideLane(portal, 0);
        }

        private void blockPositiveOutsideLane(final CustomPortalBounds portal, final int heightOffset) {
            portal.blocks().stream()
                    .filter(block -> block.y() == portal.minY())
                    .map(block -> portal.axis() == PortalAxis.X
                            ? new CustomPortalBounds.BlockPosition(
                                    block.x(), block.y() + heightOffset, block.z() + 1)
                            : new CustomPortalBounds.BlockPosition(
                                    block.x() + 1, block.y() + heightOffset, block.z()))
                    .forEach(this.obstructed::add);
        }

        private void requireClearance(final int blocks) {
            this.requiredClearance = blocks;
        }

        private void denyAllOccupancy() {
            this.denyAllOccupancy = true;
        }

        @Override
        public boolean isPortal(final CustomPortalBounds.BlockPosition block) {
            return this.portalBlocks.contains(block);
        }

        @Override
        public boolean canOccupy(final CustomPortalBounds.Destination destination) {
            if (this.denyAllOccupancy) {
                return false;
            }

            final CustomPortalBounds.BlockPosition feet = destination.feetBlock();
            for (int offset = 0; offset < this.requiredClearance; offset++) {
                if (this.obstructed.contains(new CustomPortalBounds.BlockPosition(
                        feet.x(), feet.y() + offset, feet.z()))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean hasSupport(final CustomPortalBounds.BlockPosition feet) {
            return this.supportedFeet.contains(feet);
        }
    }
}
