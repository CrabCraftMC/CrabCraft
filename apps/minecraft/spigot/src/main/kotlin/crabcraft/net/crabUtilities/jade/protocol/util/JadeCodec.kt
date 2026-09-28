package crabcraft.net.crabUtilities.jade.protocol.util

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

open class JadeCodec {
    companion object {
        @JvmField
        val PRIMITIVE_STREAM_CODEC: StreamCodec<ByteBuf, Any> =
            object : StreamCodec<ByteBuf, Any> {
                override fun decode(buf: ByteBuf): Any {
                    val type = buf.readByte().toInt()
                    return when {
                        type == 0 -> false
                        type == 1 -> true
                        type == 2 -> ByteBufCodecs.VAR_INT.decode(buf)
                        type == 3 -> ByteBufCodecs.FLOAT.decode(buf)
                        type == 4 -> ByteBufCodecs.STRING_UTF8.decode(buf)
                        type > 20 -> type - 20
                        else -> throw IllegalArgumentException("Unknown primitive type: $type")
                    }
                }

                override fun encode(buf: ByteBuf, value: Any) {
                    when (value) {
                        is Boolean -> buf.writeByte(if (value) 1 else 0)
                        is Number -> {
                            val number = value.toFloat()
                            if (number != number.toInt().toFloat()) {
                                buf.writeByte(3)
                                ByteBufCodecs.FLOAT.encode(buf, number)
                            }
                            val integer = value.toInt()
                            if (integer <= Byte.MAX_VALUE - 20 && integer >= 0) {
                                buf.writeByte(integer + 20)
                            } else {
                                ByteBufCodecs.VAR_INT.encode(buf, integer)
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
                        else -> throw IllegalArgumentException("Unknown primitive type: $value (${value.javaClass})")
                    }
                }
            }
    }
}
