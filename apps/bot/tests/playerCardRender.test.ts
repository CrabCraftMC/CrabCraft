import { randomBytes, randomInt, randomUUID } from "node:crypto";
import { expect, test } from "bun:test";
import sharp from "sharp";
import { buildPlayerInfoReply } from "../src/utils/playerInfoView.js";
import type { PlayerCardStats } from "../src/utils/playerCard.js";

test.each([["available", true], ["missing", false]] as const)("renders a player card with %s data", async (_, hasData) => {
  const target = {
    uuid: randomUUID(),
    username: hasData ? `Sim_${randomBytes(6).toString("hex")}` : "W".repeat(16),
    discordUsername: hasData ? `synthetic-${randomUUID()}` : null,
  };
  const seasons = Array.from({ length: hasData ? 2 : 1 }, () => ({
    id: randomUUID(),
    name: `Synthetic season ${randomInt(100, 1000)}`,
  }));
  const current = seasons[0]!;
  const stats: PlayerCardStats | null = hasData ? {
    play_time_seconds: randomInt(1, 1_000_000),
    total_blocks_mined: randomInt(1, 1_000_000),
    total_blocks_placed: randomInt(1, 1_000_000),
    total_items_broken: randomInt(1, 1_000_000),
    mob_kills: randomInt(1, 1_000_000),
    player_kills: randomInt(1, 1000),
    deaths: randomInt(1, 1000),
    total_distance_m: randomInt(1, 1_000_000),
    jumps: randomInt(1, 1_000_000),
    animals_bred: randomInt(1, 1000),
    fish_caught: randomInt(1, 1000),
    times_slept: randomInt(1, 1000),
  } : null;
  const crown = hasData ? {
    rank: randomInt(1, 100),
    gold: randomInt(1, 100),
    silver: randomInt(1, 100),
    bronze: randomInt(1, 100),
    crown_score: randomInt(1, 10_000),
  } : null;
  const skin = await sharp({
    create: { width: 12, height: 24, channels: 4, background: `#${randomBytes(3).toString("hex")}` },
  }).png().toBuffer();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request) => {
    const url = input instanceof Request ? input.url : String(input);
    const api = `https://api.crabcraft.net/players/${target.uuid}`;
    if (url === `${api}/stats?season=${current.id}`) return Response.json({ stats });
    if (url === `${api}/awards?season=${current.id}`) return Response.json({ crown });
    if (url === `https://mc-api.io/render/full/${target.uuid}`) return new Response(null, { status: 503 });
    if (url === `https://mc-heads.net/body/${target.uuid}/300`) {
      return hasData ? new Response(skin) : new Response(null, { status: 503 });
    }
    throw new Error(`Unexpected render request: ${url}`);
  }) as typeof fetch;

  try {
    const reply = await buildPlayerInfoReply(target, current.id, current.name, seasons);
    if ("error" in reply) throw new Error(reply.error);
    expect(reply.files).toHaveLength(1);
    expect(reply.components).toHaveLength(hasData ? 1 : 0);
    const file = reply.files[0]!;
    expect(file.name).toBe("playerinfo.png");
    if (!Buffer.isBuffer(file.attachment)) throw new Error("Expected an image attachment");
    const { info } = await sharp(file.attachment).raw().toBuffer({ resolveWithObject: true });
    expect({ width: info.width, height: info.height }).toEqual({ width: 1832, height: 1732 });
  } finally {
    globalThis.fetch = originalFetch;
  }
}, 30_000);
