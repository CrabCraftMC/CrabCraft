import type { Message } from "discord.js";
import Redis from "ioredis";
import { getStaffChatIdentity } from "./appDb.js";
import config from "./config.js";
import logger from "./logger.js";
import { relayStaffChatMessage, STAFF_CHAT_REDIS_OPTIONS } from "./staffChatRelay.js";

let redis: Redis | null = null;
let relayQueue: Promise<void> = Promise.resolve();

export function startStaffChatRelay(): void {
  if (!config.STAFF_CHAT_CHANNEL_ID || redis) return;
  redis = new Redis({
    host: config.REDIS_HOST,
    port: config.REDIS_PORT,
    password: config.REDIS_PASSWORD || undefined,
    ...STAFF_CHAT_REDIS_OPTIONS,
  });
  redis.on("error", (error) => logger.warn("Staff chat Redis connection failed:", error));
}

export async function handleStaffChatMessage(message: Message): Promise<void> {
  const connection = redis;
  if (!connection || message.guildId !== config.GUILD_ID
    || message.channelId !== config.STAFF_CHAT_CHANNEL_ID) return;
  if (connection.status !== "ready") {
    logger.warn(`Staff chat Redis is unavailable; Discord message ${message.id} dropped.`);
    return;
  }

  // Discord does not await event listeners; serialise this channel's lookups and publication.
  const queued = relayQueue.then(async () => {
    if (redis !== connection) return;
    await relayStaffChatMessage(message, {
      guildId: config.GUILD_ID,
      channelId: config.STAFF_CHAT_CHANNEL_ID,
      modRoleId: config.MOD_ROLE_ID,
      councilRoleId: config.COUNCIL_ROLE_ID,
      redisChannel: config.STAFF_CHAT_REDIS_CHANNEL,
    }, {
      redis: connection,
      lookupIdentity: getStaffChatIdentity,
      logFailure: (message, error) => logger.error(message, error),
    });
  });
  relayQueue = queued.catch((error) => {
    logger.error(`Staff chat relay failed for Discord message ${message.id}:`, error);
  });
  await relayQueue;
}

export function closeStaffChatRelay(): void {
  redis?.disconnect();
  redis = null;
}
