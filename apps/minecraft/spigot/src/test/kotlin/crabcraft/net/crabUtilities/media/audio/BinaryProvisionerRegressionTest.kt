package crabcraft.net.crabUtilities.media.audio

import java.nio.file.Files

object BinaryProvisionerRegressionTest {
  @JvmStatic
  fun main(args: Array<String>) {
    verifyRepeatedResolverFailuresAreDeduplicated()
    verifyPublishedChecksumsAreParsed()
    val file = Files.createTempFile("crabutilities-sha256", ".bin")
    try {
      check(BinaryProvisioner.hasSha256(file,
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        "empty file should match the known SHA-256 digest")
      check(!BinaryProvisioner.hasSha256(file,
          "0000000000000000000000000000000000000000000000000000000000000000"),
        "a mismatched SHA-256 digest must be rejected")
    } finally {
      Files.deleteIfExists(file)
    }
  }

  private fun verifyPublishedChecksumsAreParsed() {
    val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    val checksums = """
      # yt-dlp release checksums
      0000000000000000000000000000000000000000000000000000000000000000  yt-dlp
      ${expected.uppercase()}  yt-dlp_linux
    """.trimIndent()
    check(expected == BinaryProvisioner.publishedSha256(checksums, "yt-dlp_linux"),
      "the latest Linux binary checksum should be parsed and normalised")
    check(BinaryProvisioner.publishedSha256(checksums, "missing") == null,
      "a missing release asset must not inherit another asset's checksum")
    check(BinaryProvisioner.publishedSha256("not-a-digest  yt-dlp_linux", "yt-dlp_linux") == null,
      "an invalid published checksum must be rejected")
  }

  private fun verifyRepeatedResolverFailuresAreDeduplicated() {
    val resolver = TrackResolver(null, null, null)
    check(resolver.shouldLogFailure("https://example.invalid/live", "authentication required"),
      "the first resolver failure should be logged")
    check(!resolver.shouldLogFailure("https://example.invalid/live", "authentication required"),
      "an identical resolver failure should not be logged repeatedly")
    check(resolver.shouldLogFailure("https://example.invalid/live", "timed out"),
      "a changed resolver failure should be logged")
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }
}
