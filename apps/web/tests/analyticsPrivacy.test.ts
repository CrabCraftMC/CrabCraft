import { describe, expect, test } from "bun:test";
import {
  sanitiseAnalyticsPath,
  sanitiseAnalyticsUrl,
  sanitisePostHogEvent,
} from "../src/lib/analyticsPrivacy";

describe("analytics privacy", () => {
  test("masks player and gallery identifiers in routes", () => {
    expect(sanitiseAnalyticsPath("/stats/raw-player-uuid")).toBe(
      "/stats/[player]",
    );
    expect(sanitiseAnalyticsPath("/gallery/discord-thread-id")).toBe(
      "/gallery/[post]",
    );
    expect(sanitiseAnalyticsPath("/tools/circle-generator")).toBe(
      "/tools/circle-generator",
    );
  });

  test("removes queries, fragments, and external referrer paths", () => {
    expect(
      sanitiseAnalyticsUrl(
        "https://www.crabcraft.net/stats/raw-uuid?tab=awards#private",
      ),
    ).toBe("https://www.crabcraft.net/stats/[player]");
    expect(
      sanitiseAnalyticsUrl(
        "https://search.example/results?q=player-name",
        true,
      ),
    ).toBe("https://search.example");
  });

  test("scrubs page titles, campaign IDs, and persisted URL properties", () => {
    const event = sanitisePostHogEvent({
      uuid: "00000000-0000-7000-8000-000000000000",
      event: "$pageview",
      properties: {
        $current_url: "https://crabcraft.net/stats/raw-uuid?secret=value",
        $pathname: "/stats/raw-uuid",
        $referrer: "https://search.example/?q=player-name",
        title: "Player Name | CrabCraft",
        utm_term: "player-name",
        source: "website",
      },
      $set_once: {
        $initial_current_url:
          "https://crabcraft.net/gallery/raw-thread-id?author=name",
      },
    });

    expect(event?.properties).toEqual({
      $current_url: "https://crabcraft.net/stats/[player]",
      $pathname: "/stats/[player]",
      $referrer: "https://search.example",
      source: "website",
    });
    expect(event?.$set_once).toEqual({
      $initial_current_url: "https://crabcraft.net/gallery/[post]",
    });
  });
});
