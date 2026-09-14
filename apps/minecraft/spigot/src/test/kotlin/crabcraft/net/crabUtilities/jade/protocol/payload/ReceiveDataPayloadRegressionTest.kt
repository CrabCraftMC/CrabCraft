package crabcraft.net.crabUtilities.jade.protocol.payload

import net.minecraft.nbt.CompoundTag

object ReceiveDataPayloadRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        keepsNormalResponsesUnchanged()
        trimsOversizedProviderData()
        trimsNestedProviderData()
        fallsBackToTargetIdentity()
        preservesEntityIdentity()
    }

    private fun keepsNormalResponsesUnchanged() {
        val identity = blockIdentity()
        val response = identity.copy()
        response.putString("provider", "normal")

        check(ReceiveDataPayload.prepareForSend(response, identity) === response,
            "a normal Jade response was unnecessarily copied or trimmed")
    }

    private fun trimsOversizedProviderData() {
        val identity = blockIdentity()
        val response = identity.copy()
        response.putByteArray("minecraft:item_storage", ByteArray(ReceiveDataPayload.MAX_SIZE * 2))

        val prepared = ReceiveDataPayload.prepareForSend(response, identity)

        check(prepared.sizeInBytes() <= ReceiveDataPayload.MAX_SIZE,
            "oversized Jade provider data was not brought under the protocol limit")
        checkBlockIdentity(prepared)
        check(response.contains("minecraft:item_storage"),
            "preparing a response mutated the provider's original NBT")
    }

    private fun trimsNestedProviderData() {
        val identity = blockIdentity()
        val response = identity.copy()
        val provider = CompoundTag()
        provider.putString("small", "kept")
        provider.putByteArray("large", ByteArray(ReceiveDataPayload.MAX_SIZE * 2))
        response.put("provider", provider)

        val prepared = ReceiveDataPayload.prepareForSend(response, identity)

        check(prepared.sizeInBytes() <= ReceiveDataPayload.MAX_SIZE,
            "nested Jade provider data was not trimmed")
        check(prepared.contains("provider"),
            "the whole provider was removed instead of its oversized nested value")
        checkBlockIdentity(prepared)
    }

    private fun fallsBackToTargetIdentity() {
        val identity = blockIdentity()
        val response = identity.copy()
        for (i in 0 until 11) {
            response.putByteArray("provider-$i", ByteArray(ReceiveDataPayload.MAX_SIZE + 1))
        }

        val prepared = ReceiveDataPayload.prepareForSend(response, identity)

        check(prepared.keySet() == identity.keySet(),
            "an irreducibly oversized response did not fall back to target identity")
        checkBlockIdentity(prepared)
    }

    private fun preservesEntityIdentity() {
        val identity = CompoundTag()
        identity.putInt("EntityId", 42)
        val response = identity.copy()
        response.putByteArray("provider", ByteArray(ReceiveDataPayload.MAX_SIZE * 2))

        val prepared = ReceiveDataPayload.prepareForSend(response, identity)

        check(prepared.sizeInBytes() <= ReceiveDataPayload.MAX_SIZE,
            "oversized entity data was not brought under the protocol limit")
        check(prepared.getIntOr("EntityId", 0) == 42,
            "Jade response lost its entity identity")
    }

    private fun blockIdentity(): CompoundTag {
        val tag = CompoundTag()
        tag.putInt("x", 12)
        tag.putInt("y", 64)
        tag.putInt("z", -8)
        tag.putString("BlockId", "minecraft:chest")
        return tag
    }

    private fun checkBlockIdentity(tag: CompoundTag) {
        check(tag.getIntOr("x", 0) == 12, "Jade response lost its x coordinate")
        check(tag.getIntOr("y", 0) == 64, "Jade response lost its y coordinate")
        check(tag.getIntOr("z", 0) == -8, "Jade response lost its z coordinate")
        check(tag.getStringOr("BlockId", "") == "minecraft:chest",
            "Jade response lost its block identity")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
