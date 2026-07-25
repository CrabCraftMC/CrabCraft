package crabcraft.net.crabUtilities.jade.protocol;

import net.minecraft.nbt.CompoundTag;

final class JadeResponseIsolationRegressionTest {

    public static void main(String[] args) {
        CompoundTag identity = new CompoundTag();
        identity.putInt("x", 12);
        identity.putInt("y", 64);
        identity.putInt("z", -8);

        CompoundTag requestData = identity.copy();
        requestData.putByteArray("minecraft:item_storage", new byte[]{1, 2, 3});
        requestData.putBoolean("Loot", true);
        requestData.putBoolean("Locked", true);
        requestData.putBoolean("SortItems", true);
        requestData.putString("untrusted-provider", "stale");

        CompoundTag response = JadeProtocol.createResponseTag(identity, requestData);

        check(response.getIntOr("x", 0) == 12
                        && response.getIntOr("y", 0) == 64
                        && response.getIntOr("z", 0) == -8,
                "Jade response lost its trusted target identity");
        check(response.getBooleanOr("SortItems", false),
                "Jade response discarded the supported item sorting request");
        check(!response.contains("minecraft:item_storage"),
                "Jade response retained stale chest contents");
        check(!response.contains("Loot") && !response.contains("Locked"),
                "Jade response retained stale container state");
        check(!response.contains("untrusted-provider"),
                "Jade response copied arbitrary client-supplied provider data");

        response.putString("provider", "fresh");
        check(!identity.contains("provider"), "Jade response mutated its trusted identity tag");
        check(requestData.contains("minecraft:item_storage"), "Jade response mutated the request tag");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
