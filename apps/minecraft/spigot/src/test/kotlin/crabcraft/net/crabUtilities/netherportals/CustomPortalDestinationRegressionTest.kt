package crabcraft.net.crabUtilities.netherportals

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler

object CustomPortalDestinationRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        normalPortalLinkedToLargePortalUsesLargePortalBottom()
        netherRoofLinkWorksInBothDirections()
        bothAxesAndNegativeCoordinatesUseCompleteBounds()
        obstructedSideFallsBackToTheClearSide()
        mountedStackUsesEntitySizedClearance()
        entityThatCannotFitKeepsPaperDestination()
        oversizedPortalsSuppressOnlyPortalPigmen()
        portalCollapseExcludesVanillaSizes()
        portalCollapseOnlyAcceptsFrameBlocksInThePortalPlane()
        overlappingCollapseRequestsAreDetectable()
        customPortalCreationPublishesPlayerAndAppliesStates()
        cancelledCustomPortalCreationAppliesNothing()
        portalFloodFillsNonRectangularShapesInBothPlanes()
        portalFloodPreservesSpanLimitsAndMinimumSize()
        portalFloodRejectsIncompleteAndOverBudgetShapes()
    }

    private fun normalPortalLinkedToLargePortalUsesLargePortalBottom() {
        val normal = rectangle(PortalAxis.X, 10, 4, 70, 2, 3)
        val large = rectangle(PortalAxis.X, 10, 4, 23, 64, 64)
        check(!normal.exceedsVanillaInteriorLimit(), "normal portals must retain Paper placement")
        check(large.exceedsVanillaInteriorLimit(), "64x64 portal was not recognized as custom-sized")
        check(
            large.minY() == 23 && large.height() == 64 && large.width() == 64,
            "complete 64x64 bounds were not retained",
        )
        val safety = FakeSafety(large)
        safety.supportInteriorBottom(large)
        val destination = destination(large, 40.2, 86.0, safety)
        check(destination.y() == 23.0, "arrival should be directly above the lower frame")
        check(safety.hasSupport(destination.feetBlock()), "arrival must have solid support")
    }

    private fun netherRoofLinkWorksInBothDirections() {
        val overworld = rectangle(PortalAxis.Z, -35, -80, 23, 64, 64)
        val netherRoof = rectangle(PortalAxis.Z, -5, -10, 129, 2, 3)
        val safety = FakeSafety(overworld)
        safety.supportInteriorBottom(overworld)
        val fromNether = destination(overworld, -79.5, -6.1, safety)
        check(fromNether.y() == 23.0, "Nether-roof Y must not pin arrival to the top of the Overworld portal at Y=86")
        check(
            !netherRoof.exceedsVanillaInteriorLimit(),
            "large-source to normal Nether destination must keep vanilla-like placement",
        )
    }

    private fun bothAxesAndNegativeCoordinatesUseCompleteBounds() {
        for (axis in PortalAxis.entries) {
            val portal = rectangle(axis, -90, -40, -12, 37, 28)
            val safety = FakeSafety(portal)
            safety.supportInteriorBottom(portal)
            val targetX = if (axis == PortalAxis.X) -68.2 else -39.5
            val targetZ = if (axis == PortalAxis.X) -39.5 else -68.2
            val destination = destination(portal, targetX, targetZ, safety)
            check(destination.y() == -12.0, "$axis portal did not use its complete lower edge")
            check(
                destination.feetBlock().x() < 0 && destination.feetBlock().z() < 0,
                "$axis portal mishandled negative block coordinates",
            )
            check(
                safety.isPortal(destination.feetBlock()),
                "$axis fallback should remain in a portal block above its lower frame",
            )
        }
    }

    private fun obstructedSideFallsBackToTheClearSide() {
        for (axis in PortalAxis.entries) {
            val portal = rectangle(axis, 5, 20, 40, 24, 30)
            val safety = FakeSafety(portal)
            safety.supportAllBottomLanes(portal)
            safety.blockPositiveOutsideLane(portal)
            val targetX = if (axis == PortalAxis.X) 12.5 else 22.0
            val targetZ = if (axis == PortalAxis.X) 22.0 else 12.5
            val destination = destination(portal, targetX, targetZ, safety)
            if (axis == PortalAxis.X)
                check(destination.z() == 19.5, "X-aligned portal chose its obstructed positive-Z side")
            else check(destination.x() == 19.5, "Z-aligned portal chose its obstructed positive-X side")
            check(safety.hasSupport(destination.feetBlock()), "$axis clear-side arrival was unsupported")
            check(safety.canOccupy(destination), "$axis clear-side arrival did not have enough clearance")
        }
    }

    private fun mountedStackUsesEntitySizedClearance() {
        for (axis in PortalAxis.entries) {
            val portal = rectangle(axis, 5, 20, 40, 24, 30)
            val safety = FakeSafety(portal)
            safety.supportAllBottomLanes(portal)
            safety.requireClearance(4)
            safety.blockPositiveOutsideLane(portal, 2)
            val targetX = if (axis == PortalAxis.X) 12.5 else 22.0
            val targetZ = if (axis == PortalAxis.X) 22.0 else 12.5
            val destination = destination(portal, targetX, targetZ, safety)
            if (axis == PortalAxis.X)
                check(destination.z() == 19.5, "X-aligned riding stack chose a side without enough vertical clearance")
            else check(destination.x() == 19.5, "Z-aligned riding stack chose a side without enough vertical clearance")
            check(destination.y() == 40.0, "$axis riding stack did not arrive at the portal bottom")
        }
    }

    private fun entityThatCannotFitKeepsPaperDestination() {
        val portal = rectangle(PortalAxis.X, 0, 5, 20, 64, 64)
        val safety = FakeSafety(portal)
        safety.supportAllBottomLanes(portal)
        safety.denyAllOccupancy()
        check(
            portal.findSafeDestination(32.5, 5.5, safety).isEmpty,
            "an entity that cannot fit safely should retain Paper's original destination",
        )
    }

    private fun oversizedPortalsSuppressOnlyPortalPigmen() {
        val normal = rectangle(PortalAxis.X, 0, 5, 20, 21, 21)
        val oversized = rectangle(PortalAxis.X, 0, 5, 20, 64, 64)
        check(
            CustomNetherPortalListener.shouldSuppressPortalSpawn(
                EntityType.ZOMBIFIED_PIGLIN,
                SpawnReason.NETHER_PORTAL,
                oversized,
            ),
            "oversized portals should suppress their zombified piglin spawns",
        )
        check(
            !CustomNetherPortalListener.shouldSuppressPortalSpawn(
                EntityType.ZOMBIFIED_PIGLIN,
                SpawnReason.NETHER_PORTAL,
                normal,
            ),
            "vanilla-sized portals must retain zombified piglin spawning",
        )
        check(
            !CustomNetherPortalListener.shouldSuppressPortalSpawn(
                EntityType.ZOMBIFIED_PIGLIN,
                SpawnReason.NATURAL,
                oversized,
            ),
            "natural zombified piglin spawning must remain unchanged",
        )
        check(
            !CustomNetherPortalListener.shouldSuppressPortalSpawn(
                EntityType.ZOMBIE,
                SpawnReason.NETHER_PORTAL,
                oversized,
            ),
            "other entity types must not be suppressed",
        )
    }

    private fun portalCollapseExcludesVanillaSizes() {
        check(
            !rectangle(PortalAxis.X, 0, 0, 10, 21, 21).exceedsVanillaInteriorLimit(),
            "21x21 portal must remain entirely vanilla-managed when its frame breaks",
        )
        check(
            rectangle(PortalAxis.X, 0, 0, 10, 22, 3).exceedsVanillaInteriorLimit(),
            "oversized width must use complete custom-portal collapse",
        )
        check(
            rectangle(PortalAxis.Z, 0, 0, 10, 3, 22).exceedsVanillaInteriorLimit(),
            "oversized height must use complete custom-portal collapse",
        )
    }

    private fun portalCollapseOnlyAcceptsFrameBlocksInThePortalPlane() {
        check(PortalAxis.X.isInPlane(BlockFace.UP), "upper X frame was rejected")
        check(PortalAxis.X.isInPlane(BlockFace.DOWN), "lower X frame was rejected")
        check(PortalAxis.X.isInPlane(BlockFace.EAST), "side X frame was rejected")
        check(PortalAxis.X.isInPlane(BlockFace.WEST), "side X frame was rejected")
        check(!PortalAxis.X.isInPlane(BlockFace.NORTH), "block outside the X portal plane could trigger collapse")
        check(!PortalAxis.X.isInPlane(BlockFace.SOUTH), "block outside the X portal plane could trigger collapse")
        check(PortalAxis.Z.isInPlane(BlockFace.UP), "upper Z frame was rejected")
        check(PortalAxis.Z.isInPlane(BlockFace.DOWN), "lower Z frame was rejected")
        check(PortalAxis.Z.isInPlane(BlockFace.NORTH), "side Z frame was rejected")
        check(PortalAxis.Z.isInPlane(BlockFace.SOUTH), "side Z frame was rejected")
        check(!PortalAxis.Z.isInPlane(BlockFace.EAST), "block outside the Z portal plane could trigger collapse")
        check(!PortalAxis.Z.isInPlane(BlockFace.WEST), "block outside the Z portal plane could trigger collapse")
    }

    private fun overlappingCollapseRequestsAreDetectable() {
        val full = rectangle(PortalAxis.X, 0, 5, 10, 64, 64)
        val subset = rectangle(PortalAxis.X, 16, 5, 10, 16, 64)
        val separate = rectangle(PortalAxis.X, 80, 5, 10, 16, 64)
        check(full.overlaps(subset), "partially-cleared portal could be queued twice")
        check(subset.overlaps(full), "collapse overlap check must be symmetric")
        check(!full.overlaps(separate), "separate custom portals were incorrectly merged for collapse")
    }

    private fun customPortalCreationPublishesPlayerAndAppliesStates() {
        val world = proxy(World::class.java)
        val player = proxy(Player::class.java)
        val updates = AtomicInteger()
        val states = portalStates(16, updates)
        val published = AtomicReference<PortalCreateEvent>()
        val applied =
            PortalShapeFinder.fireAndApplyPortal(states, world, player) { event ->
                check(updates.get() == 0, "custom portal states changed before PortalCreateEvent was published")
                published.set(event)
            }
        val event = published.get()
        check(applied, "an accepted custom portal proposal was not applied")
        check(event != null, "custom portal creation did not publish PortalCreateEvent")
        check(event.reason == PortalCreateEvent.CreateReason.FIRE, "custom portal creation used the wrong event reason")
        check(event.entity === player, "custom portal creation lost the igniting player")
        check(
            event.blocks == states && event.blocks.size == 16,
            "custom portal creation did not publish the exact proposed states",
        )
        check(
            event.blocks.all { it.type == Material.NETHER_PORTAL },
            "custom portal event did not expose proposed Nether portal states",
        )
        check(updates.get() == states.size, "accepted custom portal creation did not apply every proposed state")
    }

    private fun cancelledCustomPortalCreationAppliesNothing() {
        val updates = AtomicInteger()
        val states = portalStates(16, updates)
        val applied =
            PortalShapeFinder.fireAndApplyPortal(states, proxy(World::class.java), proxy(Player::class.java)) {
                it.isCancelled = true
            }
        check(!applied, "a cancelled custom portal proposal was reported as applied")
        check(updates.get() == 0, "a cancelled custom portal proposal changed live blocks")
    }

    private fun portalFloodFillsNonRectangularShapesInBothPlanes() {
        for (axis in PortalAxis.entries) {
            val grid = PortalGrid(axis, Material.OBSIDIAN)
            // A synthetic L shape includes all four replaceable block types.
            grid.put(0, 0, Material.AIR)
            grid.put(0, 1, Material.CAVE_AIR)
            grid.put(0, 2, Material.VOID_AIR)
            grid.put(1, 0, Material.FIRE)
            check(grid.start(4, 4, 4), "$axis enclosed L shape was rejected")
            check(grid.scheduled.get() == 1, "valid portal must schedule exactly one deferred placement")
            check(
                grid.readPositions.containsAll(grid.materials.keys),
                "flood did not visit the complete non-rectangular interior",
            )
        }
    }

    private fun portalFloodPreservesSpanLimitsAndMinimumSize() {
        for (axis in PortalAxis.entries) {
            val grid = PortalGrid(axis, Material.OBSIDIAN)
            for (flat in 0 until 4) grid.put(flat, 0, Material.AIR)
            check(grid.start(4, 3, 4), "existing coordinate-span width limit changed")
            check(!grid.start(5, 3, 4), "undersized portal was accepted")
            check(!grid.start(4, 2, 4), "portal wider than the configured span was accepted")
            val tall = PortalGrid(axis, Material.OBSIDIAN)
            for (y in 0 until 5) tall.put(0, y, Material.AIR)
            check(!tall.start(1, 4, 3), "portal taller than the configured span was accepted")
        }
    }

    private fun portalFloodRejectsIncompleteAndOverBudgetShapes() {
        val obstructed = PortalGrid(PortalAxis.X, Material.OBSIDIAN)
        obstructed.put(0, 0, Material.AIR)
        obstructed.put(0, 1, Material.STONE)
        check(!obstructed.start(1, 4, 4), "non-frame obstruction was accepted")
        check(obstructed.scheduled.get() == 0, "invalid portal scheduled a block mutation")
        val open = PortalGrid(PortalAxis.Z, Material.AIR)
        check(!open.start(1, 8, 8), "unbounded air was accepted as an enclosed portal")
        check(open.reads.get() <= 8 * 8 * 4, "open-air traversal exceeded the configured block budget")
        check(open.scheduled.get() == 0, "incomplete flood scheduled a partial portal")
        val oversized = PortalGrid(PortalAxis.X, Material.OBSIDIAN)
        for (flat in 0 until 4) for (y in 0 until 4) oversized.put(flat, y, Material.AIR)
        check(!oversized.start(1, 3, 3), "over-budget interior was partially accepted")
        check(oversized.scheduled.get() == 0, "over-budget flood scheduled a partial portal")
    }

    private class PortalGrid(private val axis: PortalAxis, private val fallback: Material) {
        val materials = HashMap<CustomPortalBounds.BlockPosition, Material>()
        private val blocks = HashMap<CustomPortalBounds.BlockPosition, Block>()
        val readPositions = HashSet<CustomPortalBounds.BlockPosition>()
        val reads = AtomicInteger()
        val scheduled = AtomicInteger()

        fun put(flat: Int, y: Int, material: Material) {
            materials[position(flat, y)] = material
        }

        private fun position(flat: Int, y: Int) =
            if (axis == PortalAxis.X) CustomPortalBounds.BlockPosition(-17 + flat, -12 + y, -31)
            else CustomPortalBounds.BlockPosition(-31, -12 + y, -17 + flat)

        fun start(minimum: Int, width: Int, height: Int): Boolean {
            val scheduler =
                proxy(BukkitScheduler::class.java) { proxy, method, args ->
                    if (method.name == "runTaskLater") {
                        check(args!![2] == 1L, "portal placement must wait until after ignition")
                        scheduled.incrementAndGet()
                    }
                    defaultValue(proxy, method.name, method.returnType, args)
                }
            val server =
                proxy(Server::class.java) { proxy, method, args ->
                    if (method.name == "getScheduler") scheduler
                    else defaultValue(proxy, method.name, method.returnType, args)
                }
            val plugin =
                proxy(Plugin::class.java) { proxy, method, args ->
                    if (method.name == "getServer") server
                    else defaultValue(proxy, method.name, method.returnType, args)
                }
            return PortalShapeFinder(
                    plugin,
                    block(position(0, 0)),
                    axis,
                    PortalSettings(setOf(Material.OBSIDIAN), minimum, width, height),
                    CustomPortalRegistry(),
                    null,
                )
                .start()
        }

        private fun block(position: CustomPortalBounds.BlockPosition): Block =
            blocks.computeIfAbsent(position) {
                proxy(Block::class.java) { proxy, method, args ->
                    when (method.name) {
                        "getX" -> position.x()
                        "getY" -> position.y()
                        "getZ" -> position.z()
                        "getLocation" ->
                            Location(null, position.x().toDouble(), position.y().toDouble(), position.z().toDouble())
                        "getType" -> {
                            reads.incrementAndGet()
                            readPositions.add(position)
                            materials.getOrDefault(position, fallback)
                        }
                        "getRelative" -> {
                            val face = args!![0] as BlockFace
                            block(
                                CustomPortalBounds.BlockPosition(
                                    position.x() + face.modX,
                                    position.y() + face.modY,
                                    position.z() + face.modZ,
                                )
                            )
                        }
                        else -> defaultValue(proxy, method.name, method.returnType, args)
                    }
                }
            }
    }

    private fun portalStates(count: Int, updates: AtomicInteger): List<BlockState> {
        val states = ArrayList<BlockState>(count)
        repeat(count) {
            states.add(
                proxy(BlockState::class.java) { proxy, method, args ->
                    when (method.name) {
                        "getType" -> Material.NETHER_PORTAL
                        "update" -> {
                            check(
                                args != null && args.size == 2 && args[0] == true && args[1] == false,
                                "portal state updates must be forced without intermediate physics",
                            )
                            updates.incrementAndGet()
                            true
                        }
                        else -> defaultValue(proxy, method.name, method.returnType, args)
                    }
                }
            )
        }
        return states
    }

    private fun <T> proxy(type: Class<T>): T =
        proxy(type) { proxy, method, args -> defaultValue(proxy, method.name, method.returnType, args) }

    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))

    private fun defaultValue(proxy: Any, methodName: String, returnType: Class<*>, args: Array<out Any?>?): Any? {
        if (methodName == "equals") return proxy === args!![0]
        if (methodName == "hashCode") return System.identityHashCode(proxy)
        if (methodName == "toString") return "test-proxy"
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0.0F
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }

    private fun rectangle(
        axis: PortalAxis,
        flatStart: Int,
        plane: Int,
        bottomY: Int,
        width: Int,
        height: Int,
    ): CustomPortalBounds {
        val blocks = ArrayList<CustomPortalBounds.BlockPosition>(width * height)
        for (flat in flatStart until flatStart + width) for (y in bottomY until bottomY + height) {
            blocks.add(
                if (axis == PortalAxis.X) CustomPortalBounds.BlockPosition(flat, y, plane)
                else CustomPortalBounds.BlockPosition(plane, y, flat)
            )
        }
        return CustomPortalBounds(axis, blocks)
    }

    private fun destination(
        portal: CustomPortalBounds,
        targetX: Double,
        targetZ: Double,
        safety: FakeSafety,
    ): CustomPortalBounds.Destination =
        portal.findSafeDestination(targetX, targetZ, safety).orElseThrow { AssertionError("no safe destination found") }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private class FakeSafety(portal: CustomPortalBounds) : CustomPortalBounds.Safety {
        private val portalBlocks = portal.blocks()
        private val supportedFeet = HashSet<CustomPortalBounds.BlockPosition>()
        private val obstructed = HashSet<CustomPortalBounds.BlockPosition>()
        private var requiredClearance = 2
        private var denyAllOccupancy = false

        fun supportInteriorBottom(portal: CustomPortalBounds) {
            portal.blocks().filter { it.y() == portal.minY() }.forEach(supportedFeet::add)
        }

        fun supportAllBottomLanes(portal: CustomPortalBounds) {
            portal
                .blocks()
                .filter { it.y() == portal.minY() }
                .forEach { block ->
                    supportedFeet.add(block)
                    if (portal.axis() == PortalAxis.X) {
                        supportedFeet.add(CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() - 1))
                        supportedFeet.add(CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() + 1))
                    } else {
                        supportedFeet.add(CustomPortalBounds.BlockPosition(block.x() - 1, block.y(), block.z()))
                        supportedFeet.add(CustomPortalBounds.BlockPosition(block.x() + 1, block.y(), block.z()))
                    }
                }
        }

        fun blockPositiveOutsideLane(portal: CustomPortalBounds, heightOffset: Int = 0) {
            portal
                .blocks()
                .filter { it.y() == portal.minY() }
                .map { block ->
                    if (portal.axis() == PortalAxis.X)
                        CustomPortalBounds.BlockPosition(block.x(), block.y() + heightOffset, block.z() + 1)
                    else CustomPortalBounds.BlockPosition(block.x() + 1, block.y() + heightOffset, block.z())
                }
                .forEach(obstructed::add)
        }

        fun requireClearance(blocks: Int) {
            requiredClearance = blocks
        }

        fun denyAllOccupancy() {
            denyAllOccupancy = true
        }

        override fun isPortal(block: CustomPortalBounds.BlockPosition) = portalBlocks.contains(block)

        override fun canOccupy(destination: CustomPortalBounds.Destination): Boolean {
            if (denyAllOccupancy) return false
            val feet = destination.feetBlock()
            for (offset in 0 until requiredClearance) {
                if (obstructed.contains(CustomPortalBounds.BlockPosition(feet.x(), feet.y() + offset, feet.z())))
                    return false
            }
            return true
        }

        override fun hasSupport(feet: CustomPortalBounds.BlockPosition) = supportedFeet.contains(feet)
    }
}
