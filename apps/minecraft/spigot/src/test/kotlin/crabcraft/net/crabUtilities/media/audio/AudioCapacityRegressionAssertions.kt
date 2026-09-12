package crabcraft.net.crabUtilities.media.audio

object AudioCapacityRegressionAssertions {
  @JvmStatic
  fun verify() {
    verifySessionLimitRecovers()
    verifyWorkQueueIsBoundedAndRejectsGracefully()
  }

  private fun verifySessionLimitRecovers() {
    val limiter = AudioEngine.SessionLimiter(2)
    check(limiter.tryAcquire(), "first audio session was rejected")
    check(limiter.tryAcquire(), "second audio session was rejected")
    check(!limiter.tryAcquire(), "audio session cap was not enforced")

    limiter.release()
    check(limiter.tryAcquire(), "released audio capacity was not reusable")
    limiter.release()
    limiter.release()
    check(limiter.availablePermits() == 2, "audio session permits were not fully restored")
  }

  private fun verifyWorkQueueIsBoundedAndRejectsGracefully() {
    val executor = AudioEngine.createAudioExecutor()
    try {
      check(executor.queue.remainingCapacity() == AudioEngine.AUDIO_WORK_QUEUE_CAPACITY,
        "audio executor queue is not bounded to the configured capacity")
      executor.shutdownNow()
      check(!AudioEngine.executeIfCapacity(executor) {},
        "rejected audio work escaped the non-throwing submission path")
    } finally {
      executor.shutdownNow()
    }
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }
}
