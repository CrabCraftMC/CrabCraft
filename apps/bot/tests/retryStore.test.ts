import { expect, test } from "bun:test";
import { mock } from "node:test";
import { clearRetry, getRetry, storeRetry } from "../src/utils/retryStore.js";

test("application answers remain available when a username retry is delayed", () => {
  const userId = "delayed-applicant";
  const answers = {
    type: "full" as const,
    age: 24,
    joinReason: "I'd like to join the community.",
    aboutYou: "I enjoy building with other players.",
    referralSource: "A friend",
  };

  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    storeRetry(userId, answers);
    mock.timers.tick(24 * 60 * 60 * 1000);
    expect(getRetry(userId)).toEqual(answers);
    expect(getRetry(userId)).toBeNull();
  } finally {
    clearRetry(userId);
    mock.timers.reset();
  }
});
