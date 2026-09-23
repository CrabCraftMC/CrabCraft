import type { Client } from "discord.js";
import Redis from "ioredis";
import {
  configureHalloweenEvent,
  getPendingHalloweenRoles,
  markHalloweenRoleAwarded,
} from "@crabcraft/db/queries/halloween";
import { consumeHalloweenActions, consumeHalloweenProgressRequests } from "./halloweenTransport.js";
import config from "./config.js";
import logger from "./logger.js";

const ACTIVE_KEY = "crabcraft:halloween:active-event";

function redisClient() {
  return new Redis({
    host: config.REDIS_HOST, port: config.REDIS_PORT,
    password: config.REDIS_PASSWORD || undefined,
    lazyConnect: true, maxRetriesPerRequest: null,
  });
}

async function deliverRoles(client: Client) {
  for (const row of await getPendingHalloweenRoles()) {
    // Keep unlinked accounts pending so linking later still grants the reward.
    if (!row.discordId || !row.roleId) continue;
    try {
      const guild = await client.guilds.fetch(row.guildId);
      const member = await guild.members.fetch(row.discordId);
      if (!member.roles.cache.has(row.roleId)) {
        await member.roles.add(row.roleId, `Completed all three challenges for ${row.eventId}`);
      }
      await markHalloweenRoleAwarded(row.eventId, row.minecraftUuid);
    } catch (error) {
      logger.warn(`Halloween role delivery for ${row.minecraftUuid} will retry: ${(error as Error).message}`);
    }
  }
}


export function startHalloweenService(client: Client) {
  if (!config.HALLOWEEN_EVENT_ID || !Number.isInteger(config.HALLOWEEN_STARTS_AT)
    || !Number.isInteger(config.HALLOWEEN_ENDS_AT) || config.HALLOWEEN_STARTS_AT <= 0
    || config.HALLOWEEN_ENDS_AT <= config.HALLOWEEN_STARTS_AT) {
    logger.error("Halloween requires an event ID and valid start/end Unix timestamps.");
    return;
  }
  if (!config.HALLOWEEN_ROLE_ID) logger.warn("Halloween reward is pending configuration: set roles.halloween.");
  const redis = redisClient();
  let busy = false;
  let consuming = false;
  const reconcile = async () => {
    if (busy) return;
    busy = true;
    try {
      await configureHalloweenEvent({
        id: config.HALLOWEEN_EVENT_ID,
        starts_at: config.HALLOWEEN_STARTS_AT,
        ends_at: config.HALLOWEEN_ENDS_AT,
        guild_id: config.GUILD_ID,
        role_id: config.HALLOWEEN_ROLE_ID,
      });
      const now = Math.floor(Date.now() / 1000);
      if (config.HALLOWEEN_ENABLED && now < config.HALLOWEEN_ENDS_AT) {
        await redis.set(ACTIVE_KEY, JSON.stringify({
          id: config.HALLOWEEN_EVENT_ID,
          startsAt: config.HALLOWEEN_STARTS_AT,
          endsAt: config.HALLOWEEN_ENDS_AT,
        }), "EXAT", config.HALLOWEEN_ENDS_AT);
      } else {
        await redis.del(ACTIVE_KEY);
      }
      if (!consuming) {
        consuming = true;
        void consumeHalloweenActions(redisClient());
        void consumeHalloweenProgressRequests(redisClient(), config.HALLOWEEN_EVENT_ID, config.HALLOWEEN_ENABLED);
      }
      await deliverRoles(client);
    } catch (error) {
      logger.error("Halloween reconciliation failed:", error);
    } finally {
      busy = false;
    }
  };
  void reconcile();
  setInterval(reconcile, 30_000);
}
