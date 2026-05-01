package crabcraft.net.crabUtilities.voicechat;

import java.io.ByteArrayOutputStream;

/**
 * Minecraft VarInt encoder. We need this for the {@code String} field
 * inside SVC's {@code PlayerStatePacket} wire format, which is encoded
 * as a VarInt-length-prefix followed by UTF-8 bytes (the same encoding
 * Mojang's {@code FriendlyByteBuf.writeUtf} uses).
 */
final class VarInt {

    private VarInt() {}

    static void write(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }
}
