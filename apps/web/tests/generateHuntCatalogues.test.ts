import { describe, expect, test } from "bun:test";
import { parseMobs } from "../scripts/generate-hunt-catalogues";

describe("hunt catalogue generation", () => {
  test("excludes future mobs listed after the stable Java mobs", () => {
    const entries = parseMobs([
      "=== Passive mobs ===", "{{Mob icon|Cow}}",
      "=== Neutral mobs ===", "{{Mob icon|Enderman}}",
      "=== Hostile mobs ===", "{{Mob icon|Creeper}}",
      "=== Upcoming mobs ===", "{{Mob icon|Blub}}", "{{Mob icon|Frostbite}}",
      "=== Creators or Education mobs ===", "{{Mob icon|Agent}}",
    ].join("\n"));
    expect(entries.map((entry) => entry.name)).toEqual(["Cow", "Enderman", "Creeper"]);
  });

  test("accepts the previous education heading when there is no upcoming section", () => {
    const entries = parseMobs([
      "=== Passive mobs ===", "{{Mob icon|Cow}}",
      "=== Creators or education mobs ===", "{{Mob icon|Agent}}",
    ].join("\n"));
    expect(entries.map((entry) => entry.name)).toEqual(["Cow"]);
  });
});
