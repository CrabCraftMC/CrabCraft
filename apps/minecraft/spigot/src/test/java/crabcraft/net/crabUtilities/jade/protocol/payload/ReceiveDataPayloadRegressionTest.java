package crabcraft.net.crabUtilities.jade.protocol.payload;

import net.minecraft.nbt.CompoundTag;

final class ReceiveDataPayloadRegressionTest {

    public static void main(String[] args) {
        keepsNormalResponsesUnchanged();
        trimsOversizedProviderData();
        trimsNestedProviderData();
        fallsBackToTargetIdentity();
        preservesEntityIdentity();
    }

    private static void keepsNormalResponsesUnchanged() {
        CompoundTag identity = blockIdentity();
        CompoundTag response = identity.copy();
        response.putString("provider", "normal");

        check(ReceiveDataPayload.prepareForSend(response, identity) == response,
                "a normal Jade response was unnecessarily copied or trimmed");
    }

    private static void trimsOversizedProviderData() {
        CompoundTag identity = blockIdentity();
        CompoundTag response = identity.copy();
        response.putByteArray("minecraft:item_storage", new byte[ReceiveDataPayload.MAX_SIZE * 2]);

        CompoundTag prepared = ReceiveDataPayload.prepareForSend(response, identity);

        check(prepared.sizeInBytes() <= ReceiveDataPayload.MAX_SIZE,
                "oversized Jade provider data was not brought under the protocol limit");
        checkBlockIdentity(prepared);
        check(response.contains("minecraft:item_storage"),
                "preparing a response mutated the provider's original NBT");
    }

    private static void trimsNestedProviderData() {
        CompoundTag identity = blockIdentity();
        CompoundTag response = identity.copy();
        CompoundTag provider = new CompoundTag();
        provider.putString("small", "kept");
        provider.putByteArray("large", new byte[ReceiveDataPayload.MAX_SIZE * 2]);
        response.put("provider", provider);

        CompoundTag prepared = ReceiveDataPayload.prepareForSend(response, identity);

        check(prepared.sizeInBytes() <= ReceiveDataPayload.MAX_SIZE,
                "nested Jade provider data was not trimmed");
        check(prepared.contains("provider"),
                "the whole provider was removed instead of its oversized nested value");
        checkBlockIdentity(prepared);
    }

    private static void fallsBackToTargetIdentity() {
        CompoundTag identity = blockIdentity();
        CompoundTag response = identity.copy();
        for (int i = 0; i < 11; i++) {
            response.putByteArray("provider-" + i, new byte[ReceiveDataPayload.MAX_SIZE + 1]);
        }

        CompoundTag prepared = ReceiveDataPayload.prepareForSend(response, identity);

        check(prepared.keySet().equals(identity.keySet()),
                "an irreducibly oversized response did not fall back to target identity");
        checkBlockIdentity(prepared);
    }

    private static void preservesEntityIdentity() {
        CompoundTag identity = new CompoundTag();
        identity.putInt("EntityId", 42);
        CompoundTag response = identity.copy();
        response.putByteArray("provider", new byte[ReceiveDataPayload.MAX_SIZE * 2]);

        CompoundTag prepared = ReceiveDataPayload.prepareForSend(response, identity);

        check(prepared.sizeInBytes() <= ReceiveDataPayload.MAX_SIZE,
                "oversized entity data was not brought under the protocol limit");
        check(prepared.getIntOr("EntityId", 0) == 42,
                "Jade response lost its entity identity");
    }

    private static CompoundTag blockIdentity() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", 12);
        tag.putInt("y", 64);
        tag.putInt("z", -8);
        tag.putString("BlockId", "minecraft:chest");
        return tag;
    }

    private static void checkBlockIdentity(CompoundTag tag) {
        check(tag.getIntOr("x", 0) == 12, "Jade response lost its x coordinate");
        check(tag.getIntOr("y", 0) == 64, "Jade response lost its y coordinate");
        check(tag.getIntOr("z", 0) == -8, "Jade response lost its z coordinate");
        check(tag.getStringOr("BlockId", "").equals("minecraft:chest"),
                "Jade response lost its block identity");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
