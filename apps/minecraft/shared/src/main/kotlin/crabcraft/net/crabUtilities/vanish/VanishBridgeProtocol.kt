package crabcraft.net.crabUtilities.vanish

/** Wire format used to report EssentialsX vanish state from Paper to Velocity. */
object VanishBridgeProtocol {
    const val CHANNEL = "crabcraft:vanish"
    private const val VERSION: Byte = 1
    private const val VISIBLE: Byte = 0
    private const val VANISHED: Byte = 1

    @JvmStatic fun status(vanished: Boolean): ByteArray = byteArrayOf(VERSION, if (vanished) VANISHED else VISIBLE)

    @JvmStatic
    fun decode(payload: ByteArray?): Boolean {
        require(payload != null && payload.size == 2 && payload[0] == VERSION) {
            "Invalid vanish status payload"
        }
        return when (payload[1]) {
            VISIBLE -> false
            VANISHED -> true
            else -> throw IllegalArgumentException("Unknown vanish status value")
        }
    }
}
