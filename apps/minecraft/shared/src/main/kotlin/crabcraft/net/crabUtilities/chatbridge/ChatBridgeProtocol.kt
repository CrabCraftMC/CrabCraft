package crabcraft.net.crabUtilities.chatbridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Wire format shared by the Paper and Velocity CrabUtilities plugins. */
object ChatBridgeProtocol {
    const val CHANNEL = "crabcraft:chat_bridge"
    private const val MAX_PAYLOAD_BYTES = 30_000
    private const val MAX_STRING_BYTES = 28_000

    enum class Type(val id: Int) {
        PRIVATE_REQUEST(1), REPLY_REQUEST(2), STAFF_REQUEST(3), DELIVERY(4), STAFF_STATE(5);

        companion object {
            fun fromId(id: Int): Type = entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown chat bridge packet type: $id")
        }
    }

    data class Packet(
        val type: Type,
        val playerId: UUID?,
        val target: String?,
        val content: String?,
        val enabled: Boolean
    ) {
        fun type() = type
        fun playerId() = playerId
        fun target() = target
        fun content() = content
        fun enabled() = enabled
    }

    @JvmStatic
    fun privateRequest(target: String?, message: String?): ByteArray = encode(Type.PRIVATE_REQUEST) {
        writeString(it, target)
        writeString(it, message)
    }

    @JvmStatic
    fun replyRequest(message: String?): ByteArray = encode(Type.REPLY_REQUEST) { writeString(it, message) }

    @JvmStatic
    fun staffRequest(componentJson: String?): ByteArray = encode(Type.STAFF_REQUEST) { writeString(it, componentJson) }

    @JvmStatic
    fun delivery(playerId: UUID?, componentJson: String?): ByteArray = encode(Type.DELIVERY) {
        writeUuid(it, playerId)
        writeString(it, componentJson)
    }

    @JvmStatic
    fun staffState(playerId: UUID?, enabled: Boolean): ByteArray = encode(Type.STAFF_STATE) {
        writeUuid(it, playerId)
        it.writeBoolean(enabled)
    }

    @JvmStatic
    fun decode(payload: ByteArray?): Packet {
        require(payload != null && payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid chat bridge payload length"
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val type = Type.fromId(input.readUnsignedByte())
                val packet = when (type) {
                    Type.PRIVATE_REQUEST -> Packet(type, null, readString(input), readString(input), false)
                    Type.REPLY_REQUEST, Type.STAFF_REQUEST -> Packet(type, null, null, readString(input), false)
                    Type.DELIVERY -> Packet(type, readUuid(input), null, readString(input), false)
                    Type.STAFF_STATE -> Packet(type, readUuid(input), null, null, input.readBoolean())
                }
                require(input.available() == 0) { "Trailing chat bridge payload data" }
                return packet
            }
        } catch (exception: IOException) {
            throw IllegalArgumentException("Malformed chat bridge payload", exception)
        }
    }

    private fun encode(type: Type, writer: (DataOutputStream) -> Unit): ByteArray {
        try {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeByte(type.id)
                writer(output)
            }
            val payload = bytes.toByteArray()
            require(payload.size <= MAX_PAYLOAD_BYTES) { "Chat bridge payload is too large" }
            return payload
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to encode chat bridge payload", exception)
        }
    }

    private fun writeString(output: DataOutputStream, value: String?) {
        require(value != null) { "Chat bridge strings cannot be null" }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Chat bridge string is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length >= 0 && length <= MAX_STRING_BYTES && length <= input.available()) {
            "Invalid chat bridge string length"
        }
        return String(input.readNBytes(length), StandardCharsets.UTF_8)
    }

    private fun writeUuid(output: DataOutputStream, uuid: UUID?) {
        require(uuid != null) { "Chat bridge UUID cannot be null" }
        output.writeLong(uuid.mostSignificantBits)
        output.writeLong(uuid.leastSignificantBits)
    }

    private fun readUuid(input: DataInputStream) = UUID(input.readLong(), input.readLong())
}
