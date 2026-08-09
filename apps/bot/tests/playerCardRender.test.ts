import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { describe, expect, test } from "bun:test";
import { fromJsx } from "@takumi-rs/helpers/jsx";
import { AttachmentBuilder } from "discord.js";
import sharp from "sharp";
import { buildPlayerInfoReply, type Season } from "../src/utils/playerInfoView.js";
import type { PlayerCardStats } from "../src/utils/playerCard.js";

/**
 * Update these visually approved player-card goldens intentionally with:
 * UPDATE_PLAYER_CARD_GOLDENS=1 bun test tests/playerCardRender.test.ts
 * Then visually review the changed PNGs and update their expected raw hashes
 * before committing them.
 */

type Fixture = {
  slug: "full-player-card" | "edge-fallback-card";
  golden: string;
  target: { uuid: string; username: string; discordUsername: string | null };
  seasonId: string;
  seasonName: string;
  seasons: Season[];
  stats: PlayerCardStats | null;
  crown: { rank: number; gold: number; silver: number; bronze: number; crown_score: number } | null;
  skin: "local-fallback" | "unavailable";
  expectedComponents: number;
  expectedRawSha256: string;
};

const FIXTURE_DIR = path.join(import.meta.dir, "fixtures", "player-card");
const LOCAL_SKIN = fs.readFileSync(path.join(FIXTURE_DIR, "local-skin.png"));
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const WIDTH = 1832;
const HEIGHT = 1732;

const fixtures: Fixture[] = [
  {
    slug: "full-player-card",
    golden: "full-player-card.golden.png",
    target: {
      uuid: "11111111-2222-3333-4444-555555555555",
      username: "CrabPlayer",
      discordUsername: "crab.friend",
    },
    seasonId: "season-2",
    seasonName: "Season Two",
    seasons: [
      { id: "season-2", name: "Season Two" },
      { id: "season-1", name: "Season One" },
    ],
    stats: {
      play_time_seconds: 987654,
      total_blocks_mined: 1234567,
      total_blocks_placed: 765432,
      total_items_broken: 23456,
      mob_kills: 34567,
      player_kills: 456,
      deaths: 78,
      total_distance_m: 9876543,
      jumps: 876543,
      animals_bred: 654,
      fish_caught: 321,
      times_slept: 210,
    },
    crown: { rank: 7, gold: 12, silver: 34, bronze: 56, crown_score: 7890 },
    skin: "local-fallback",
    expectedComponents: 1,
    expectedRawSha256: "62b23dcbb239e0c5be2581a0e0914786d4644586d2a0d26f901b6c092ed5ccec",
  },
  {
    slug: "edge-fallback-card",
    golden: "edge-fallback-card.golden.png",
    target: {
      uuid: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      username: "WWWWWWWWWWWWWWWW",
      discordUsername: null,
    },
    seasonId: "edge-season",
    seasonName: "Season Ω",
    seasons: [{ id: "edge-season", name: "Season Ω" }],
    stats: null,
    crown: null,
    skin: "unavailable",
    expectedComponents: 0,
    expectedRawSha256: "a6f4fc0eb4a7a70b3ad371c3a0d4eb3948b8e53ea5be53d6b673fb7b6c85c2af",
  },
];

const sha256 = (data: Uint8Array) => createHash("sha256").update(data).digest("hex");

function fixtureFetch(fixture: Fixture): typeof fetch {
  return (async (input: string | URL | Request) => {
    const url = input instanceof Request ? input.url : String(input);
    if (!url.includes(fixture.target.uuid)) throw new Error(`unexpected fetch URL: ${url}`);

    if (url === `https://api.crabcraft.net/players/${fixture.target.uuid}/stats?season=${fixture.seasonId}`) {
      return Response.json({ username: fixture.target.username, stats: fixture.stats });
    }
    if (url === `https://api.crabcraft.net/players/${fixture.target.uuid}/awards?season=${fixture.seasonId}`) {
      return Response.json({ username: fixture.target.username, crown: fixture.crown });
    }
    if (url === `https://mc-api.io/render/full/${fixture.target.uuid}`) {
      return new Response(null, { status: 503 });
    }
    if (url === `https://mc-heads.net/body/${fixture.target.uuid}/300`) {
      return fixture.skin === "local-fallback"
        ? new Response(LOCAL_SKIN, { status: 200, headers: { "content-type": "image/png" } })
        : new Response(null, { status: 503 });
    }

    throw new Error(`unexpected fetch URL: ${url}`);
  }) as typeof fetch;
}

async function renderFixture(fixture: Fixture): Promise<Buffer> {
  const reply = await buildPlayerInfoReply(
    fixture.target,
    fixture.seasonId,
    fixture.seasonName,
    fixture.seasons,
  );
  if ("error" in reply) throw new Error(`${fixture.slug}: ${reply.error}`);

  expect(reply.files).toHaveLength(1);
  expect(reply.components).toHaveLength(fixture.expectedComponents);
  const file = reply.files[0];
  expect(file).toBeInstanceOf(AttachmentBuilder);
  expect(file.name).toBe("playerinfo.png");
  if (!Buffer.isBuffer(file.attachment)) throw new Error(`${fixture.slug}: attachment was not a Buffer`);
  expect(file.attachment.subarray(0, 8)).toEqual(PNG_SIGNATURE);

  if (fixture.expectedComponents === 1) {
    expect(reply.components.map((component) => component.toJSON())).toEqual([
      {
        type: 1,
        components: [
          {
            type: 3,
            custom_id: `playerinfo:season:${fixture.target.uuid}`,
            placeholder: fixture.seasonName,
            options: [
              { label: "Season Two", value: "season-2", default: true },
              { label: "Season One", value: "season-1", default: false },
            ],
          },
        ],
      },
    ]);
  }

  return file.attachment;
}

async function expectGoldenMatch(fixture: Fixture, actualPng: Buffer): Promise<void> {
  const goldenPath = path.join(FIXTURE_DIR, fixture.golden);
  if (process.env.UPDATE_PLAYER_CARD_GOLDENS === "1") await Bun.write(goldenPath, actualPng);

  const [actual, golden] = await Promise.all([
    sharp(actualPng).ensureAlpha().raw().toBuffer({ resolveWithObject: true }),
    sharp(goldenPath).ensureAlpha().raw().toBuffer({ resolveWithObject: true }),
  ]);
  for (const image of [actual, golden]) {
    expect(image.info.width).toBe(WIDTH);
    expect(image.info.height).toBe(HEIGHT);
    expect(image.info.channels).toBe(4);
  }
  expect(sha256(golden.data)).toBe(fixture.expectedRawSha256);

  let changedPixels = 0;
  for (let offset = 0; offset < actual.data.length; offset += 4) {
    if (
      actual.data[offset] !== golden.data[offset] ||
      actual.data[offset + 1] !== golden.data[offset + 1] ||
      actual.data[offset + 2] !== golden.data[offset + 2] ||
      actual.data[offset + 3] !== golden.data[offset + 3]
    ) {
      changedPixels++;
    }
  }
  if (changedPixels > 0) {
    throw new Error(
      `${fixture.slug}: ${changedPixels} / ${WIDTH * HEIGHT} pixels changed from the approved golden`,
    );
  }
}

describe("player-card production rendering", () => {
  for (const fixture of fixtures) {
    test(
      `${fixture.slug} matches its approved golden`,
      async () => {
        const originalFetch = globalThis.fetch;
        globalThis.fetch = fixtureFetch(fixture);
        try {
          const first = await renderFixture(fixture);
          const second = await renderFixture(fixture);
          expect(first.equals(second)).toBe(true);
          await expectGoldenMatch(fixture, first);
        } finally {
          globalThis.fetch = originalFetch;
        }
      },
      30_000,
    );
  }
});

test("Takumi helpers converts a React-shaped intrinsic JSX tree", async () => {
  const result = await fromJsx(
    {
      type: "div",
      props: {
        style: { display: "flex" },
        children: { type: "span", props: { children: "CrabCraft" } },
      },
    },
    { defaultStyles: false },
  );

  expect(result.stylesheets).toEqual([]);
  expect(result.node).toMatchObject({
    type: "container",
    tagName: "div",
    style: { display: "flex" },
    children: [{ type: "text", tagName: "span", text: "CrabCraft" }],
  });
});
