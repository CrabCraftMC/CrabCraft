import { expect, test } from "bun:test";
import { withCountingQueue } from "../src/utils/countingQueue.js";

test("counting queue rejects work beyond its per-channel bound", async () => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let executions = 0;

  const accepted = Array.from({ length: 25 }, () =>
    withCountingQueue("bounded-channel", async () => {
      executions++;
      await gate;
    }),
  );

  const overflow = await withCountingQueue("bounded-channel", async () => {
    executions++;
  });
  expect(overflow).toBe(false);

  release();
  expect(await Promise.all(accepted)).toEqual(Array(25).fill(true));
  expect(executions).toBe(25);
});
