import type { Message } from "discord.js";
import Redis from "ioredis";
import { getStaffChatIdentity } from "./appDb.js";
import config from "./config.js";
import logger from "./logger.js";
import { relayStaffChatMessage, STAFF_CHAT_REDIS_OPTIONS } from "./staffChatRelay.js";

let redis: Redis | null = null;

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
  if (!redis) return;
  await relayStaffChatMessage(message, {
    guildId: config.GUILD_ID,
    channelId: config.STAFF_CHAT_CHANNEL_ID,
    modRoleId: config.MOD_ROLE_ID,
    councilRoleId: config.COUNCIL_ROLE_ID,
    redisChannel: config.STAFF_CHAT_REDIS_CHANNEL,
  }, {
    redis,
    lookupIdentity: getStaffChatIdentity,
    logFailure: (message, error) => logger.error(message, error),
  });
}

export function closeStaffChatRelay(): void {
  redis?.disconnect();
  redis = null;
}
