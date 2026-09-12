package crabcraft.net.crabUtilities.slime

import org.bukkit.Material
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import java.util.HashSet

/** Regression coverage for the yaw-relative 9x6 slime-map layout. */
object SlimeMapRegressionTest {
    private const val CENTER_SLOT = 31
    @JvmStatic
    fun main(args: Array<String>) {
        centerSlotAlwaysRepresentsThePlayerChunk()
        mapTopFollowsThePlayersFacingDirection()
        everyCardinalLayoutContains54DistinctChunks()
        slimeBallsOpenTheMapOnRightClick()
    }
    private fun centerSlotAlwaysRepresentsThePlayerChunk() {
        for (yaw in floatArrayOf(-180.0F, -90.0F, 0.0F, 90.0F, 180.0F)) checkOffset(yaw, CENTER_SLOT, 0, 0)
    }
    private fun mapTopFollowsThePlayersFacingDirection() {
        val oneSlotForward = CENTER_SLOT - 9
        checkOffset(0.0F, oneSlotForward, 0, 1)
        checkOffset(90.0F, oneSlotForward, -1, 0)
        checkOffset(-90.0F, oneSlotForward, 1, 0)
        checkOffset(180.0F, oneSlotForward, 0, -1)
    }
    private fun everyCardinalLayoutContains54DistinctChunks() {
        for (yaw in floatArrayOf(-90.0F, 0.0F, 90.0F, 180.0F)) {
            val offsets = HashSet<SlimeMap.ChunkOffset>()
            for (slot in 0 until 54) offsets.add(SlimeMap.chunkOffsetAt(yaw, slot))
            check(offsets.size == 54, "yaw $yaw produced duplicate chunk positions")
        }
    }
    private fun slimeBallsOpenTheMapOnRightClick() {
        check(SlimeMapListener.shouldOpenMap(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.SLIME_BALL, Material.SLIME_BALL), "main-hand slime-ball air clicks should open the map")
        check(SlimeMapListener.shouldOpenMap(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND, Material.SLIME_BALL, Material.STONE), "off-hand slime-ball block clicks should open the map")
        check(!SlimeMapListener.shouldOpenMap(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, Material.SLIME_BALL, Material.SLIME_BALL), "left clicks should not open the map")
        check(!SlimeMapListener.shouldOpenMap(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.STONE, Material.STONE), "other held items should not open the map")
        check(!SlimeMapListener.shouldOpenMap(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, Material.SLIME_BALL, Material.SLIME_BALL), "two held slime balls should not open the map twice")
    }
    private fun checkOffset(yaw: Float, slot: Int, expectedX: Int, expectedZ: Int) {
        val actual = SlimeMap.chunkOffsetAt(yaw, slot)
        check(actual.x() == expectedX && actual.z() == expectedZ, "yaw $yaw, slot $slot mapped to $actual instead of ($expectedX, $expectedZ)")
    }

    private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
