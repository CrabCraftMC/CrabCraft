package crabcraft.net.crabUtilities.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat

object UpdateDownloaderRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val jar = "paper-update".toByteArray(StandardCharsets.UTF_8)
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/checksum") { exchange -> respond(exchange, checksum + "  CrabUtilities.jar\n", "application/octet-stream") }
        server.createContext("/jar") { exchange -> respond(exchange, jar, "application/octet-stream") }
        server.start()
        val targetDir = Files.createTempDirectory("crabutilities-update-test")
        try {
            val baseUrl = "http://127.0.0.1:" + server.address.port
            val release = ReleaseInfo("v1.1.0", SemVer.parse("v1.1.0"), "CrabUtilities.jar", baseUrl + "/jar", baseUrl + "/checksum", jar.size.toLong(), false)
            val downloaded = UpdateDownloader("", "CrabUtilities regression test").download(release, targetDir, "CrabUtilities.jar")
            check(Arrays.equals(jar, Files.readAllBytes(downloaded)), "the verified Paper update should be downloaded")
        } finally {
            server.stop(0)
            Files.deleteIfExists(targetDir.resolve("CrabUtilities.jar"))
            Files.deleteIfExists(targetDir)
        }
    }
    private fun respond(exchange: HttpExchange, body: String, expectedAccept: String) { respond(exchange, body.toByteArray(StandardCharsets.UTF_8), expectedAccept) }
    private fun respond(exchange: HttpExchange, body: ByteArray, expectedAccept: String) {
        if (expectedAccept != exchange.requestHeaders.getFirst("Accept")) { exchange.sendResponseHeaders(406, -1); exchange.close(); return }
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
