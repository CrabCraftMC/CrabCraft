package crabcraft.net.crabUtilities.velocity.api

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object WebServerRequestBodyRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val allowed = "a".repeat(64 * 1024).toByteArray(StandardCharsets.UTF_8)
        val decoded = WebServer.readRequestBody(ByteArrayInputStream(allowed))
        check(decoded.length == allowed.size, "64 KiB request should be accepted")

        val oversized = "a".repeat(64 * 1024 + 1).toByteArray(StandardCharsets.UTF_8)
        var rejected = false
        try {
            WebServer.readRequestBody(ByteArrayInputStream(oversized))
        } catch (expected: IOException) {
            rejected = true
        }
        check(rejected, "request larger than 64 KiB should be rejected")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
