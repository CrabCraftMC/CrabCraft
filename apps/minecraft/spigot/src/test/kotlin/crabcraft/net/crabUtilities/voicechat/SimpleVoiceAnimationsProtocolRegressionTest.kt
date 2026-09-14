package crabcraft.net.crabUtilities.voicechat

import java.nio.ByteBuffer
import java.util.UUID

object SimpleVoiceAnimationsProtocolRegressionTest {
  @JvmStatic fun main(args: Array<String>) {
    decodesClientPreferences()
    clampsClientPreferencesLikeTheMod()
    rejectsMalformedClientPreferences()
    encodesPlayerPreferences()
  }

  private fun decodesClientPreferences() {
    val payload = ByteBuffer.allocate(9).put(4.toByte()).putFloat(2.5F).putFloat(1.25F).array()
    val preferences = SimpleVoiceAnimationsIntegration.decodePreferences(payload)
    check(preferences.headAnimationStyle() == 4, "head animation style was decoded incorrectly")
    check(preferences.splitHeight() == 2.5F, "split height was decoded incorrectly")
    check(preferences.intensity() == 1.25F, "intensity was decoded incorrectly")
  }

  private fun clampsClientPreferencesLikeTheMod() {
    val payload = ByteBuffer.allocate(9).put(1.toByte()).putFloat(Float.POSITIVE_INFINITY).putFloat(Float.NaN).array()
    val preferences = SimpleVoiceAnimationsIntegration.decodePreferences(payload)
    check(preferences.splitHeight() == 0.5F, "non-finite split height did not use the mod fallback")
    check(preferences.intensity() == 1F, "non-finite intensity did not use the mod fallback")
  }

  private fun rejectsMalformedClientPreferences() {
    expectInvalid(byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0, 0))
    expectInvalid(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    expectInvalid(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
  }

  private fun encodesPlayerPreferences() {
    val playerId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val preferences = SimpleVoiceAnimationsIntegration.Preferences(5, 2F, 1.25F)
    val expected = byteArrayOf(
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
      0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
      0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
      0x05, 0x40, 0x00, 0x00, 0x00, 0x3F, 0xA0.toByte(), 0x00, 0x00)
    val actual = SimpleVoiceAnimationsIntegration.encodePlayerPreferences(playerId, preferences)
    check(actual.contentEquals(expected), "player preferences payload was encoded incorrectly")
  }

  private fun expectInvalid(payload: ByteArray) {
    try {
      SimpleVoiceAnimationsIntegration.decodePreferences(payload)
      throw AssertionError("malformed preferences payload was accepted")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
  }

  private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
