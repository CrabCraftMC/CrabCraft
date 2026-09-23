import { describe, expect, test } from "bun:test";
import {
  advanceHalloweenProgress, HALLOWEEN_MOBS, isHalloweenComplete,
  isNewerStreamId, parseHalloweenAction,
} from "@crabcraft/db/halloweenLogic";

const empty = () => ({ hunt_mask: 0, trick_or_treat: false, back_from_the_dead: false });

describe("Halloween hunt and reward eligibility", () => {
  test("repeated kills cannot substitute for distinct types", () => {
    let progress = empty();
    for (let i = 0; i < 5; i++) progress = advanceHalloweenProgress(progress, "zombie");
    expect(progress.hunt_mask).toBe(1);
    expect(isHalloweenComplete(progress)).toBe(false);
  });

  test("death resets an unfinished hunt but preserves the other challenges", () => {
    let progress = advanceHalloweenProgress(empty(), "trick_or_treat");
    progress = advanceHalloweenProgress(progress, "back_from_the_dead");
    progress = advanceHalloweenProgress(progress, "witch");
    progress = advanceHalloweenProgress(progress, "death");
    expect(progress).toEqual({ hunt_mask: 0, trick_or_treat: true, back_from_the_dead: true });
    expect(isHalloweenComplete(progress)).toBe(false);
    for (const mob of HALLOWEEN_MOBS.slice(0, 4)) progress = advanceHalloweenProgress(progress, mob);
    expect(isHalloweenComplete(progress)).toBe(false);
    progress = advanceHalloweenProgress(progress, "witch");
    expect(isHalloweenComplete(progress)).toBe(true);
  });

  test("all three are required in any completion order; a completed hunt survives death", () => {
    for (const order of [
      [...HALLOWEEN_MOBS, "trick_or_treat", "back_from_the_dead"],
      ["back_from_the_dead", "trick_or_treat", ...HALLOWEEN_MOBS],
    ] as const) {
      let progress = empty();
      for (const action of order) {
        expect(isHalloweenComplete(progress)).toBe(false);
        progress = advanceHalloweenProgress(progress, action);
      }
      expect(isHalloweenComplete(progress)).toBe(true);
      expect(advanceHalloweenProgress(progress, "death")).toEqual(progress);
    }
    let hunt = empty();
    for (const mob of HALLOWEEN_MOBS) hunt = advanceHalloweenProgress(hunt, mob);
    expect(advanceHalloweenProgress(hunt, "death")).toEqual(hunt);
    expect(isHalloweenComplete(hunt)).toBe(false);
  });

  test("PostgreSQL replay guard rejects duplicate and older events, comparing sequences numerically", () => {
    const millis = BigInt(Math.floor(Math.random() * 100_000) + 1_000_000);
    expect(isNewerStreamId(`${millis}-10`, `${millis}-9`)).toBe(true);
    expect(isNewerStreamId(`${millis}-9`, `${millis}-10`)).toBe(false);
    expect(isNewerStreamId(`${millis}-10`, `${millis}-10`)).toBe(false);
    expect(isNewerStreamId(`${millis + 1n}-0`, `${millis}-99`)).toBe(true);
  });

  test("stream validation rejects invalid actions and malformed identities", () => {
    const fields = {
      event_id: `fictional-${crypto.randomUUID()}`,
      minecraft_uuid: crypto.randomUUID(),
      occurred_at: String(Math.floor(Math.random() * 100_000) + 1_000_000),
      action: "death",
    };
    expect(parseHalloweenAction(fields)?.action).toBe("death");
    expect(parseHalloweenAction({ ...fields, action: "claim_reward" })).toBeNull();
    expect(parseHalloweenAction({ ...fields, minecraft_uuid: "invalid" })).toBeNull();
    expect(parseHalloweenAction({ ...fields, occurred_at: "NaN" })).toBeNull();
    expect(parseHalloweenAction({ ...fields, occurred_at: "" })).toBeNull();
  });
});
