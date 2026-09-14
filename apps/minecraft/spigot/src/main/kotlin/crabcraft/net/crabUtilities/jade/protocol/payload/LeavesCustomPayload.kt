package crabcraft.net.crabUtilities.jade.protocol.payload

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** Leaf's custom payload marker and runtime field annotations for codec discovery. */
interface LeavesCustomPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = LEAVES_TYPE

    @Target(AnnotationTarget.FIELD)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class ID

    @Target(AnnotationTarget.FIELD)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Codec

    companion object {
        @JvmField val LEAVES_TYPE: CustomPacketPayload.Type<out CustomPacketPayload> =
            CustomPacketPayload.Type<CustomPacketPayload>(Identifier.fromNamespaceAndPath("leaves", "custom_payload"))
    }
}
