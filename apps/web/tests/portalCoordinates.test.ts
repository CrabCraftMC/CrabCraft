import { describe, expect, test } from "bun:test";
import { parsePortalCoordinates } from "../src/data/portal-coordinates";

describe("Portal coordinate pastes", () => {
  test.each([
    ["100, 64, 100", { x: 100, z: 100 }],
    ["100 64 100", { x: 100, z: 100 }],
    ["100, 100", { x: 100, z: 100 }],
    ["100 100", { x: 100, z: 100 }],
    ["-240, 72, 320", { x: -240, z: 320 }],
  ])("parses %s as X and Z", (raw, expected) => {
    expect(parsePortalCoordinates(raw)).toEqual(expected);
  });

  test.each(["100", "100 64 100 20", "100 east", ""])(
    "leaves invalid coordinate groups alone: %s",
    (raw) => {
      expect(parsePortalCoordinates(raw)).toBeNull();
    },
  );
});
