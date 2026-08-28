package crabcraft.net.crabUtilities.media.audio;

import java.nio.file.Files;

public final class BinaryProvisionerRegressionTest {

  public static void main(String[] args) throws Exception {
    verifyRepeatedResolverFailuresAreDeduplicated();
    verifyPublishedChecksumsAreParsed();
    var file = Files.createTempFile("crabutilities-sha256", ".bin");
    try {
      check(BinaryProvisioner.hasSha256(file,
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        "empty file should match the known SHA-256 digest");
      check(!BinaryProvisioner.hasSha256(file,
          "0000000000000000000000000000000000000000000000000000000000000000"),
        "a mismatched SHA-256 digest must be rejected");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static void verifyPublishedChecksumsAreParsed() {
    String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    String checksums = """
      # yt-dlp release checksums
      0000000000000000000000000000000000000000000000000000000000000000  yt-dlp
      %s  yt-dlp_linux
      """.formatted(expected.toUpperCase());
    check(expected.equals(BinaryProvisioner.publishedSha256(checksums, "yt-dlp_linux")),
      "the latest Linux binary checksum should be parsed and normalised");
    check(BinaryProvisioner.publishedSha256(checksums, "missing") == null,
      "a missing release asset must not inherit another asset's checksum");
    check(BinaryProvisioner.publishedSha256("not-a-digest  yt-dlp_linux", "yt-dlp_linux") == null,
      "an invalid published checksum must be rejected");
  }

  private static void verifyRepeatedResolverFailuresAreDeduplicated() {
    TrackResolver resolver = new TrackResolver(null, null, null);
    check(resolver.shouldLogFailure("https://example.invalid/live", "authentication required"),
      "the first resolver failure should be logged");
    check(!resolver.shouldLogFailure("https://example.invalid/live", "authentication required"),
      "an identical resolver failure should not be logged repeatedly");
    check(resolver.shouldLogFailure("https://example.invalid/live", "timed out"),
      "a changed resolver failure should be logged");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
