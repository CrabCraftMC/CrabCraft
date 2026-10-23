import { MessageType } from "discord.js";
import type { RedisOptions } from "ioredis";

// Live chat must fail promptly rather than replaying buffered messages later.
export const STAFF_CHAT_REDIS_OPTIONS = {
  connectTimeout: 5000,
  commandTimeout: 5000,
  enableOfflineQueue: false,
  maxRetriesPerRequest: 0,
  autoResendUnfulfilledCommands: false,
  retryStrategy: (attempt: number) => Math.min(attempt * 1000, 3000),
} satisfies RedisOptions;

export interface StaffChatMessage {
  id: string;
  guildId: string | null;
  channelId: string;
  system: boolean;
  type: MessageType;
  webhookId: string | null;
  content: string;
  author: { id: string; username: string; bot: boolean };
  member: { roles: { cache: { has(id: string): boolean } } } | null;
}

export interface StaffChatIdentity {
  minecraft_uuid: string | null;
  minecraft_username: string | null;
  nickname: string | null;
  nickname_raw: string | null;
}

export interface StaffChatRelayConfig {
  guildId: string;
  channelId: string;
  modRoleId: string;
  councilRoleId: string;
  redisChannel: string;
}

export interface StaffChatRelayDependencies {
  redis: {
    status: string;
    hget(key: string, field: string): Promise<string | null>;
    publish(channel: string, message: string): Promise<number>;
  };
  lookupIdentity(discordId: string): Promise<StaffChatIdentity | null>;
  logFailure(message: string, error: unknown): void;
}

/** Relay human staff messages only; nickname styling is rendered by Velocity. */
export async function relayStaffChatMessage(
  message: StaffChatMessage,
  config: StaffChatRelayConfig,
  dependencies: StaffChatRelayDependencies,
): Promise<boolean> {
  if (!config.channelId || message.guildId !== config.guildId
    || message.channelId !== config.channelId || message.author.bot
    || message.webhookId || message.system
    || (message.type !== MessageType.Default && message.type !== MessageType.Reply)) {
    return false;
  }
  if (!message.member || !(message.member.roles.cache.has(config.modRoleId)
    || message.member.roles.cache.has(config.councilRoleId))) {
    return false;
  }

  const text = message.content.replace(/[\u0000-\u001f\u007f\s]+/g, " ").trim();
  if (!text) return false;

  try {
    if (dependencies.redis.status !== "ready") {
      throw new Error("Redis is unavailable; live staff message dropped.");
    }
    const identity = await dependencies.lookupIdentity(message.author.id);
    let sender = message.author.username;
    let nicknameRaw: string | null = null;

    if (identity?.minecraft_uuid) {
      sender = identity.minecraft_username || sender;
      const cached = await dependencies.redis.hget(
        "crabutilities:nicknames", identity.minecraft_uuid,
      );
      if (cached !== null) {
        // An empty value records a nickname removal and overrides stale SQL.
        nicknameRaw = cached || null;
      } else if (identity.nickname_raw) {
        nicknameRaw = identity.nickname_raw;
      } else if (identity.nickname) {
        sender = identity.nickname;
      }
    }

    await dependencies.redis.publish(config.redisChannel, JSON.stringify({
      source: "discord",
      sender,
      nicknameRaw,
      message: text,
      messageId: message.id,
    }));
    return true;
  } catch (error) {
    dependencies.logFailure(`Staff chat relay failed for Discord message ${message.id}:`, error);
    return false;
  }
}
