package crabcraft.net.crabUtilities.slime

import org.bukkit.Material
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot

object SlimeMapRegressionTest {
    private const val CENTER_SLOT = 31

    @JvmStatic
    fun main(args: Array<String>) {
        centreSlotAlwaysRepresentsThePlayerChunk()
        mapTopFollowsThePlayersFacingDirection()
        everyCardinalLayoutContains54DistinctChunks()
        slimeBallsOpenTheMapOnRightClick()
    }

    private fun centreSlotAlwaysRepresentsThePlayerChunk() {
        for (yaw in floatArrayOf(-180f, -90f, 0f, 90f, 180f)) checkOffset(yaw, CENTER_SLOT, 0, 0)
    }

    private fun mapTopFollowsThePlayersFacingDirection() {
        val forward = CENTER_SLOT - 9
        checkOffset(0f, forward, 0, 1)
        checkOffset(90f, forward, -1, 0)
        checkOffset(-90f, forward, 1, 0)
        checkOffset(180f, forward, 0, -1)
    }

    private fun everyCardinalLayoutContains54DistinctChunks() {
        for (yaw in floatArrayOf(-90f, 0f, 90f, 180f)) {
            val offsets = HashSet<SlimeMap.ChunkOffset>()
            for (slot in 0 until 54) offsets.add(SlimeMap.chunkOffsetAt(yaw, slot))
            check(offsets.size == 54, "yaw $yaw produced duplicate chunk positions")
        }
    }

    private fun slimeBallsOpenTheMapOnRightClick() {
        check(
            SlimeMapListener.shouldOpenMap(
                Action.RIGHT_CLICK_AIR,
                EquipmentSlot.HAND,
                Material.SLIME_BALL,
                Material.SLIME_BALL,
            ),
            "main-hand slime-ball air clicks should open the map",
        )
        check(
            SlimeMapListener.shouldOpenMap(
                Action.RIGHT_CLICK_BLOCK,
                EquipmentSlot.OFF_HAND,
                Material.SLIME_BALL,
                Material.STONE,
            ),
            "off-hand slime-ball block clicks should open the map",
        )
        check(
            !SlimeMapListener.shouldOpenMap(
                Action.LEFT_CLICK_AIR,
                EquipmentSlot.HAND,
                Material.SLIME_BALL,
                Material.SLIME_BALL,
            ),
            "left clicks should not open the map",
        )
        check(
            !SlimeMapListener.shouldOpenMap(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.STONE, Material.STONE),
            "other held items should not open the map",
        )
        check(
            !SlimeMapListener.shouldOpenMap(
                Action.RIGHT_CLICK_AIR,
                EquipmentSlot.OFF_HAND,
                Material.SLIME_BALL,
                Material.SLIME_BALL,
            ),
            "two held slime balls should not open the map twice",
        )
    }

    private fun checkOffset(yaw: Float, slot: Int, expectedX: Int, expectedZ: Int) {
        val actual = SlimeMap.chunkOffsetAt(yaw, slot)
        check(
            actual.x() == expectedX && actual.z() == expectedZ,
            "yaw $yaw, slot $slot mapped to $actual instead of ($expectedX, $expectedZ)",
        )
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
