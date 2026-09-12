package crabcraft.net.crabUtilities.jade.protocol.util

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

open class JadeCodec {
    companion object {
        @JvmField
        val PRIMITIVE_STREAM_CODEC: StreamCodec<ByteBuf, Any> = object : StreamCodec<ByteBuf, Any> {
            override fun decode(buf: ByteBuf): Any {
                val b = buf.readByte().toInt()
                return when {
                    b == 0 -> false
                    b == 1 -> true
                    b == 2 -> ByteBufCodecs.VAR_INT.decode(buf)
                    b == 3 -> ByteBufCodecs.FLOAT.decode(buf)
                    b == 4 -> ByteBufCodecs.STRING_UTF8.decode(buf)
                    b > 20 -> b - 20
                    else -> throw IllegalArgumentException("Unknown primitive type: $b")
                }
            }

            override fun encode(buf: ByteBuf, value: Any) {
                when (value) {
                    is Boolean -> buf.writeByte(if (value) 1 else 0)
                    is Number -> {
                        val f = value.toFloat()
                        if (f != f.toInt().toFloat()) {
                            buf.writeByte(3)
                            ByteBufCodecs.FLOAT.encode(buf, f)
                        }
                        val i = value.toInt()
                        if (i <= Byte.MAX_VALUE - 20 && i >= 0) {
                            buf.writeByte(i + 20)
                        } else {
                            ByteBufCodecs.VAR_INT.encode(buf, i)
                        }
                    }
                    is String -> {
                        buf.writeByte(4)
                        ByteBufCodecs.STRING_UTF8.encode(buf, value)
                    }
                    is Enum<*> -> {
                        buf.writeByte(4)
                        ByteBufCodecs.STRING_UTF8.encode(buf, value.name)
                    }
                    else -> throw IllegalArgumentException("Unknown primitive type: %s (%s)".format(value, value.javaClass))
                }
            }
        }
    }
}
