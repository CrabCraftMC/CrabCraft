import sharp from "sharp";
import logger from "./logger.js";
import type { Client } from "discord.js";
import type { PlayerStats } from "./leaderboard.js";

const EMOJI_PREFIX = "lb_";
const CANVAS_SIZE = 128;
const HEAD_SIZE = 80;
const CORNER_RADIUS = 20;
const PADDING = (CANVAS_SIZE - HEAD_SIZE) / 2;

/** Fetch a player's head, apply a squircle mask, and pad with transparency for spacing. */
async function generateSquircleAvatar(uuid: string): Promise<Buffer> {
  const res = await fetch(`https://mc-heads.net/avatar/${uuid}/${HEAD_SIZE}`);
  if (!res.ok) throw new Error(`mc-heads.net returned ${res.status}`);

  const avatar = Buffer.from(await res.arrayBuffer());

  const mask = Buffer.from(
    `<svg width="${HEAD_SIZE}" height="${HEAD_SIZE}">
      <rect x="0" y="0" width="${HEAD_SIZE}" height="${HEAD_SIZE}" rx="${CORNER_RADIUS}" ry="${CORNER_RADIUS}" fill="white"/>
    </svg>`,
  );

  const head = await sharp(avatar)
    .resize(HEAD_SIZE, HEAD_SIZE)
    .composite([{ input: mask, blend: "dest-in" }])
    .png()
    .toBuffer();

  return sharp({
    create: { width: CANVAS_SIZE, height: CANVAS_SIZE, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  })
    .composite([{ input: head, left: PADDING, top: PADDING }])
    .png()
    .toBuffer();
}

/** Emoji name for a given UUID (first 8 chars, stable across name changes). */
function emojiName(uuid: string): string {
  return `${EMOJI_PREFIX}${uuid.replace(/-/g, "").slice(0, 8)}`;
}

let syncing = false;

/**
 * Sync application emojis to match the current top 10 players.
 * Creates emojis for new players, deletes emojis for players who dropped off.
 * Returns a map of uuid → emoji string (e.g. "<:lb_c5ef3347:123456789>").
 */
export async function syncLeaderboardEmojis(
  client: Client,
  players: PlayerStats[],
): Promise<Map<string, string>> {
  const emojiMap = new Map<string, string>();

  if (syncing) {
    // Return current emojis without modifying
    try {
      const existing = await client.application!.emojis.fetch();
      for (const player of players) {
        const name = emojiName(player.uuid);
        const emoji = existing.find((e) => e.name === name);
        if (emoji) emojiMap.set(player.uuid, `<:${emoji.name}:${emoji.id}>`);
      }
    } catch {
      // Ignore
    }
    return emojiMap;
  }

  syncing = true;
  try {
    const existing = await client.application!.emojis.fetch();

    const wantedNames = new Set(players.map((p) => emojiName(p.uuid)));

    // Delete emojis no longer needed
    for (const [, emoji] of existing) {
      if (emoji.name?.startsWith(EMOJI_PREFIX) && !wantedNames.has(emoji.name)) {
        try {
          await client.application!.emojis.delete(emoji.id!);
        } catch (e) {
          logger.error(`Failed to delete emoji ${emoji.name}:`, e);
        }
      }
    }

    // Create or reuse emojis for current players
    for (const player of players) {
      const name = emojiName(player.uuid);
      const existingEmoji = existing.find((e) => e.name === name);

      if (existingEmoji) {
        emojiMap.set(player.uuid, `<:${existingEmoji.name}:${existingEmoji.id}>`);
        continue;
      }

      try {
        const avatar = await generateSquircleAvatar(player.uuid);
        const emoji = await client.application!.emojis.create({
          name,
          attachment: avatar,
        });
        emojiMap.set(player.uuid, `<:${emoji.name}:${emoji.id}>`);
      } catch (e) {
        logger.error(`Failed to create emoji for ${player.name}:`, e);
      }
    }
  } catch (e) {
    logger.error("Failed to sync leaderboard emojis:", e);
  } finally {
    syncing = false;
  }

  return emojiMap;
}
