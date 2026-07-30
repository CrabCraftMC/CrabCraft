package crabcraft.net.crabUtilities.slime;

import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.Set;

/** Regression coverage for the yaw-relative 9x6 slime-map layout. */
public final class SlimeMapRegressionTest {

    private static final int CENTER_SLOT = 31;

    private SlimeMapRegressionTest() {}

    public static void main(String[] args) {
        centerSlotAlwaysRepresentsThePlayerChunk();
        mapTopFollowsThePlayersFacingDirection();
        everyCardinalLayoutContains54DistinctChunks();
        slimeBallsOpenTheMapOnRightClick();
    }

    private static void centerSlotAlwaysRepresentsThePlayerChunk() {
        for (float yaw : new float[] { -180.0F, -90.0F, 0.0F, 90.0F, 180.0F }) {
            checkOffset(yaw, CENTER_SLOT, 0, 0);
        }
    }

    private static void mapTopFollowsThePlayersFacingDirection() {
        int oneSlotForward = CENTER_SLOT - 9;
        checkOffset(0.0F, oneSlotForward, 0, 1);
        checkOffset(90.0F, oneSlotForward, -1, 0);
        checkOffset(-90.0F, oneSlotForward, 1, 0);
        checkOffset(180.0F, oneSlotForward, 0, -1);
    }

    private static void everyCardinalLayoutContains54DistinctChunks() {
        for (float yaw : new float[] { -90.0F, 0.0F, 90.0F, 180.0F }) {
            Set<SlimeMap.ChunkOffset> offsets = new HashSet<>();
            for (int slot = 0; slot < 54; slot++) {
                offsets.add(SlimeMap.chunkOffsetAt(yaw, slot));
            }
            check(offsets.size() == 54, "yaw " + yaw + " produced duplicate chunk positions");
        }
    }

    private static void slimeBallsOpenTheMapOnRightClick() {
        check(SlimeMapListener.shouldOpenMap(
                        Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.SLIME_BALL, Material.SLIME_BALL),
                "main-hand slime-ball air clicks should open the map");
        check(SlimeMapListener.shouldOpenMap(
                        Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND, Material.SLIME_BALL, Material.STONE),
                "off-hand slime-ball block clicks should open the map");
        check(!SlimeMapListener.shouldOpenMap(
                        Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, Material.SLIME_BALL, Material.SLIME_BALL),
                "left clicks should not open the map");
        check(!SlimeMapListener.shouldOpenMap(
                        Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.STONE, Material.STONE),
                "other held items should not open the map");
        check(!SlimeMapListener.shouldOpenMap(
                        Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, Material.SLIME_BALL, Material.SLIME_BALL),
                "two held slime balls should not open the map twice");
    }

    private static void checkOffset(float yaw, int slot, int expectedX, int expectedZ) {
        SlimeMap.ChunkOffset actual = SlimeMap.chunkOffsetAt(yaw, slot);
        check(actual.x() == expectedX && actual.z() == expectedZ,
                "yaw " + yaw + ", slot " + slot + " mapped to " + actual
                        + " instead of (" + expectedX + ", " + expectedZ + ")");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
