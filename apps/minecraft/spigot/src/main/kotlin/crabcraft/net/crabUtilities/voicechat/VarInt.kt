package crabcraft.net.crabUtilities.voicechat

import java.io.ByteArrayOutputStream

/** Minecraft VarInt encoding for SVC's UTF-8 PlayerStatePacket fields. */
object VarInt {
    @JvmStatic
    fun write(out: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining and 0x7F.inv() != 0) {
            out.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining and 0x7F)
    }
}
