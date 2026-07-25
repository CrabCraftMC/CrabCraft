package crabcraft.net.crabUtilities.media.audio;

import java.util.concurrent.ThreadPoolExecutor;

public final class AudioCapacityRegressionAssertions {
  private AudioCapacityRegressionAssertions() {}

  public static void verify() {
    verifySessionLimitRecovers();
    verifyWorkQueueIsBoundedAndRejectsGracefully();
  }

  private static void verifySessionLimitRecovers() {
    AudioEngine.SessionLimiter limiter = new AudioEngine.SessionLimiter(2);
    check(limiter.tryAcquire(), "first audio session was rejected");
    check(limiter.tryAcquire(), "second audio session was rejected");
    check(!limiter.tryAcquire(), "audio session cap was not enforced");

    limiter.release();
    check(limiter.tryAcquire(), "released audio capacity was not reusable");
    limiter.release();
    limiter.release();
    check(limiter.availablePermits() == 2, "audio session permits were not fully restored");
  }

  private static void verifyWorkQueueIsBoundedAndRejectsGracefully() {
    ThreadPoolExecutor executor = AudioEngine.createAudioExecutor();
    try {
      check(executor.getQueue().remainingCapacity() == AudioEngine.AUDIO_WORK_QUEUE_CAPACITY,
        "audio executor queue is not bounded to the configured capacity");
      executor.shutdownNow();
      check(!AudioEngine.executeIfCapacity(executor, () -> {}),
        "rejected audio work escaped the non-throwing submission path");
    } finally {
      executor.shutdownNow();
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
