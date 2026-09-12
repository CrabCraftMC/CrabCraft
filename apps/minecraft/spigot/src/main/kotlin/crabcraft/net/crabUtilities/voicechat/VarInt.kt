package crabcraft.net.crabUtilities.voicechat

import java.io.ByteArrayOutputStream

/** Minecraft VarInt encoder for SVC's UTF-8 player-state string length prefix. */
class VarInt private constructor() {
    companion object {
        @JvmStatic fun write(out: ByteArrayOutputStream, value: Int) {
            var remaining = value
            while ((remaining and 0x7F.inv()) != 0) {
                out.write((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            out.write(remaining and 0x7F)
        }
    }
}
