import type { Client } from "discord.js";
import * as appDb from "./appDb.js";
import { fetchPlayerName } from "./mojang.js";
import logger from "./logger.js";
import { IDENTITY_SYNC_INTERVAL_MS } from "./constants.js";

interface SyncStats {
  discordChecked: number;
  discordUpdated: number;
  discordFailed: number;
  minecraftChecked: number;
  minecraftUpdated: number;
  minecraftFailed: number;
}

function blankStats(): SyncStats {
  return {
    discordChecked: 0,
    discordUpdated: 0,
    discordFailed: 0,
    minecraftChecked: 0,
    minecraftUpdated: 0,
    minecraftFailed: 0,
  };
}

async function getMinecraftName(
  uuid: string,
  cache: Map<string, string | null>,
): Promise<string | null> {
  if (cache.has(uuid)) return cache.get(uuid) ?? null;

  const name = await fetchPlayerName(uuid);
  const currentName = name === "Unknown" ? null : name;
  cache.set(uuid, currentName);
  return currentName;
}

async function runIdentitySync(client: Client): Promise<void> {
  const stats = blankStats();
  const minecraftNameCache = new Map<string, string | null>();
  const players = await appDb.getSyncablePlayerIdentities();

  for (const player of players) {
    stats.discordChecked += 1;
    const user = await client.users
      .fetch(player.discord_id, { force: true })
      .catch(() => null);

    if (!user) {
      stats.discordFailed += 1;
    } else if (user.username !== player.discord_username) {
      await appDb.updatePlayerDiscordUsername(player.discord_id, user.username);
      stats.discordUpdated += 1;
    }

    if (!player.minecraft_uuid) continue;

    stats.minecraftChecked += 1;
    const currentName = await getMinecraftName(player.minecraft_uuid, minecraftNameCache);
    if (!currentName) {
      stats.minecraftFailed += 1;
    } else if (currentName !== player.minecraft_username) {
      await appDb.updatePlayerMinecraftUsername(player.minecraft_uuid, currentName);
      stats.minecraftUpdated += 1;
    }
  }

  const alts = await appDb.getSyncablePlayerAltIdentities();
  for (const alt of alts) {
    stats.minecraftChecked += 1;
    const currentName = await getMinecraftName(alt.minecraft_uuid, minecraftNameCache);
    if (!currentName) {
      stats.minecraftFailed += 1;
    } else if (currentName !== alt.minecraft_username) {
      await appDb.updateAltMinecraftUsername(alt.minecraft_uuid, currentName);
      stats.minecraftUpdated += 1;
    }
  }

  logger.info(
    `Identity sync complete: Discord ${stats.discordUpdated}/${stats.discordChecked} updated (${stats.discordFailed} failed), Minecraft ${stats.minecraftUpdated}/${stats.minecraftChecked} updated (${stats.minecraftFailed} failed)`,
  );
}

export function startIdentitySync(client: Client): void {
  let running = false;

  const sync = async () => {
    if (running) return;
    running = true;
    try {
      await runIdentitySync(client);
    } catch (error) {
      logger.error("Identity sync failed:", error);
    } finally {
      running = false;
    }
  };

  void sync();
  setInterval(sync, IDENTITY_SYNC_INTERVAL_MS);
}
