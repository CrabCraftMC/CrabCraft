import {
  ActionRowBuilder,
  AttachmentBuilder,
  ButtonBuilder,
  ButtonStyle,
  type Client,
  type TextChannel,
} from "discord.js";
import Redis from "ioredis";
import { createHash } from "node:crypto";
import {
  getActiveBingoCard,
  getBingoCardStartingBetween,
  getPendingBingoMilestones,
  markBingoCardPosted,
  markBingoMilestoneDelivered,
  recordBingoCompletion,
  seedBingoCard,
} from "@crabcraft/db/queries/bingo";
import config from "./config.js";
import logger from "./logger.js";
import { PREPARED_BINGO_CARDS, SUPPORTED_BINGO_TASK_IDS } from "./bingoDefinitions.js";
import { generateBingoCardImage } from "./bingoView.js";
import { AnalyticsEvent } from "@crabcraft/shared/analytics";
import { captureMinecraftEvent } from "./analytics.js";

const RECONCILE_INTERVAL_MS = 30_000;
const REDIS_BLOCK_MS = 10_000;
const REDIS_RECONNECT_MS = 3_000;
const NEXT_CARD_START_WINDOW_SECONDS = 86_400;

interface CompletionEvent {
  cardId: number;
  minecraftUuid: string;
  taskId: string;
  completedAt: number;
  sourceBackend: string | null;
}

type RedisStreamReadResponse = Array<[string, Array<[string, string[]]>]>;

function redisClient(): Redis {
  return new Redis({
    host: config.REDIS_HOST,
    port: config.REDIS_PORT,
    password: config.REDIS_PASSWORD || undefined,
    lazyConnect: true,
    maxRetriesPerRequest: null,
  });
}

function jumpButton(guildId: string, channelId: string, messageId: string) {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setLabel("View bingo card")
      .setStyle(ButtonStyle.Link)
      .setURL(`https://discord.com/channels/${guildId}/${channelId}/${messageId}`),
  );
}

function validateCard(card: NonNullable<Awaited<ReturnType<typeof getActiveBingoCard>>>): void {
  const ids = card.tasks.map((task) => task.id);
  if (ids.length !== 16 || new Set(ids).size !== 16) {
    throw new Error(`Bingo #${card.number} must contain 16 unique tasks`);
  }
  const unsupported = ids.filter((id) => !SUPPORTED_BINGO_TASK_IDS.has(id));
  if (unsupported.length > 0) {
    throw new Error(`Bingo #${card.number} has no deployed detector for: ${unsupported.join(", ")}`);
  }
}

async function warnIfNextCardMissing(
  redis: Redis,
  client: Client,
  card: NonNullable<Awaited<ReturnType<typeof getActiveBingoCard>>>,
  now: number,
): Promise<void> {
  const warningWindowSeconds = config.BINGO_MISSING_CARD_WARNING_HOURS * 3_600;
  const secondsRemaining = card.ends_at - now;
  if (secondsRemaining <= 0 || secondsRemaining > warningWindowSeconds) return;
  if (await getBingoCardStartingBetween(
    card.ends_at,
    card.ends_at + NEXT_CARD_START_WINDOW_SECONDS,
  )) return;

  const warningKey = `crabcraft:bingo:missing-card-warning:${card.id}`;
  if (await redis.exists(warningKey)) return;
  if (!config.LOG_CHANNEL_ID || !config.BINGO_OWNER_USER_ID) {
    logger.warn(`No card follows Bingo #${card.number}, but the warning channel or owner is not configured`);
    return;
  }

  const channel = await client.channels.fetch(config.LOG_CHANNEL_ID).catch(() => null);
  if (!channel?.isTextBased() || channel.isDMBased()) {
    logger.error("Cannot send the missing bingo card warning: log channel is unavailable");
    return;
  }
  await channel.send({
    content: `<@${config.BINGO_OWNER_USER_ID}> ⚠️ No bingo card is prepared for <t:${card.ends_at}:F>.`,
    allowedMentions: { users: [config.BINGO_OWNER_USER_ID] },
    nonce: `bingo-missing-${card.id}`,
    enforceNonce: true,
  });
  await redis.set(
    warningKey,
    "1",
    "EX",
    Math.max(86_400, secondsRemaining + 604_800),
  );
  logger.info(`Warned ${config.BINGO_OWNER_USER_ID} that no card follows Bingo #${card.number}`);
}

async function publishWeeklyCard(client: Client, card: Awaited<ReturnType<typeof getActiveBingoCard>>) {
  if (!card || card.posted_at !== null || !config.BINGO_CHANNEL_ID) return;
  validateCard(card);
  const channel = await client.channels.fetch(config.BINGO_CHANNEL_ID).catch(() => null);
  if (!channel?.isTextBased() || channel.isDMBased()) {
    logger.error("Bingo channel is missing or is not a guild text channel");
    return;
  }

  const cardImage = await generateBingoCardImage(card);
  const content = config.BINGO_PING_ROLE_ID
    ? `<@&${config.BINGO_PING_ROLE_ID}> 🦀 **This week's bingo is here!**`
    : "🦀 **This week's bingo is here!**";
  const message = await channel.send({
    content,
    files: [new AttachmentBuilder(cardImage, { name: `bingo-${card.number}.png` })],
    allowedMentions: { roles: config.BINGO_PING_ROLE_ID ? [config.BINGO_PING_ROLE_ID] : [] },
    nonce: `bingo-card-${card.id}`,
    enforceNonce: true,
  });
  await markBingoCardPosted(card.id, message.guildId!, message.channelId, message.id);
  logger.info(`Posted Bingo #${card.number} in ${message.channelId}`);
}

async function publishActiveCard(redis: Redis, client: Client): Promise<void> {
  const now = Math.floor(Date.now() / 1_000);
  let card = await getActiveBingoCard(now);
  if (!card) {
    await redis.del(config.BINGO_ACTIVE_CARD_KEY);
    return;
  }
  validateCard(card);
  await warnIfNextCardMissing(redis, client, card, now);
  await publishWeeklyCard(client, card);
  if (!card.announcement_message_id) {
    card = await getActiveBingoCard(now);
  }
  if (!card?.announcement_message_id) {
    await redis.del(config.BINGO_ACTIVE_CARD_KEY);
    return;
  }
  await redis.set(
    config.BINGO_ACTIVE_CARD_KEY,
    JSON.stringify({
      id: card.id,
      number: card.number,
      startsAt: card.starts_at,
      endsAt: card.ends_at,
      taskIds: card.tasks.map((task) => task.id),
    }),
  );
}

async function deliverRole(
  client: Client,
  row: Awaited<ReturnType<typeof getPendingBingoMilestones>>[number],
  kind: "line" | "blackout",
): Promise<void> {
  const roleId = kind === "line" ? config.BINGO_LINE_ROLE_ID : config.BINGO_BLACKOUT_ROLE_ID;
  if (!roleId || !row.guildId || !row.discordId) {
    if (!row.discordId) {
      logger.warn(`Bingo milestone for ${row.minecraftUuid} has no linked Discord account`);
      await markBingoMilestoneDelivered(row.cardId, row.minecraftUuid, kind, "role");
    }
    return;
  }
  const guild = await client.guilds.fetch(row.guildId);
  const member = await guild.members.fetch(row.discordId);
  if (!member.roles.cache.has(roleId)) {
    await member.roles.add(roleId, `Bingo #${row.cardNumber} ${kind}`);
  }
  await markBingoMilestoneDelivered(row.cardId, row.minecraftUuid, kind, "role");
}

async function deliverAnnouncement(
  client: Client,
  row: Awaited<ReturnType<typeof getPendingBingoMilestones>>[number],
  kind: "line" | "blackout",
): Promise<void> {
  if (!row.guildId || !row.channelId || !row.messageId) return;
  const channel = await client.channels.fetch(row.channelId).catch(() => null) as TextChannel | null;
  if (!channel?.isTextBased()) return;
  const username = row.username || row.minecraftUuid;
  const content = kind === "line"
    ? `🦀 **${username} completed their first line on Bingo #${row.cardNumber}!**`
    : `🏆 **${username} completed their entire Bingo #${row.cardNumber} card!**`;
  await channel.send({
    content,
    components: [jumpButton(row.guildId, row.channelId, row.messageId)],
    nonce: createHash("sha256")
      .update(`bingo-${kind}-${row.cardId}-${row.minecraftUuid}`)
      .digest("hex")
      .slice(0, 25),
    enforceNonce: true,
  });
  await markBingoMilestoneDelivered(row.cardId, row.minecraftUuid, kind, "announcement");
}

async function deliverPendingMilestones(client: Client): Promise<void> {
  for (const row of await getPendingBingoMilestones()) {
    if (row.firstLineCompletedAt !== null) {
      if (row.firstLineAnnouncedAt === null) await deliverAnnouncement(client, row, "line");
      if (row.firstLineRoleAwardedAt === null) await deliverRole(client, row, "line");
    }
    if (row.blackoutCompletedAt !== null) {
      if (row.blackoutAnnouncedAt === null) await deliverAnnouncement(client, row, "blackout");
      if (row.blackoutRoleAwardedAt === null) await deliverRole(client, row, "blackout");
    }
  }
}

function parseCompletion(raw: string[]): CompletionEvent | null {
  const fields: Record<string, string> = {};
  for (let index = 0; index < raw.length - 1; index += 2) fields[raw[index]] = raw[index + 1];
  const cardId = Number(fields.card_id);
  const completedAt = Number(fields.completed_at);
  if (!Number.isInteger(cardId) || !Number.isInteger(completedAt)
    || !fields.minecraft_uuid || !fields.task_id) return null;
  return {
    cardId,
    minecraftUuid: fields.minecraft_uuid,
    taskId: fields.task_id,
    completedAt,
    sourceBackend: fields.source_backend || null,
  };
}

async function processEntries(
  redis: Redis,
  client: Client,
  response: RedisStreamReadResponse | null,
): Promise<boolean> {
  let processed = false;
  if (!response) return processed;
  for (const [, entries] of response) {
    for (const [id, fields] of entries) {
      processed = true;
      const event = parseCompletion(fields);
      if (event) {
        const completion = await recordBingoCompletion(event);
        if (completion.inserted) {
          captureMinecraftEvent(
            completion.ownerMinecraftUuid,
            AnalyticsEvent.BINGO_SQUARE_COMPLETED,
            {
              card_id: event.cardId,
              source_backend: event.sourceBackend,
              task_id: event.taskId,
            },
            {
              dedupeKey: `${event.cardId}:${event.taskId}`,
            },
          );
        }
        await deliverPendingMilestones(client);
      } else {
        logger.warn(`Ignoring malformed bingo completion event ${id}`);
      }
      await redis.xack(config.BINGO_REDIS_STREAM, config.BINGO_REDIS_GROUP, id);
    }
  }
  return processed;
}

async function ensureGroup(redis: Redis): Promise<void> {
  try {
    await redis.xgroup("CREATE", config.BINGO_REDIS_STREAM, config.BINGO_REDIS_GROUP, "0", "MKSTREAM");
  } catch (error) {
    if (!(error as Error).message.includes("BUSYGROUP")) throw error;
  }
}

function startCompletionConsumer(client: Client): void {
  const redis = redisClient();
  // Stable name lets a restarted single bot reclaim its own unacknowledged entries.
  const consumer = "crabcraft-bot";
  void (async () => {
    while (true) {
      try {
        if (redis.status === "wait") await redis.connect();
        await ensureGroup(redis);
        logger.info(`Bingo completion consumer started as ${config.BINGO_REDIS_GROUP}/${consumer}`);
        while (true) {
          const pending = await (redis.xreadgroup as (...args: unknown[]) => Promise<unknown>)(
            "GROUP", config.BINGO_REDIS_GROUP, consumer, "COUNT", 25,
            "STREAMS", config.BINGO_REDIS_STREAM, "0",
          ) as RedisStreamReadResponse | null;
          if (await processEntries(redis, client, pending)) continue;
          const fresh = await (redis.xreadgroup as (...args: unknown[]) => Promise<unknown>)(
            "GROUP", config.BINGO_REDIS_GROUP, consumer, "BLOCK", REDIS_BLOCK_MS, "COUNT", 25,
            "STREAMS", config.BINGO_REDIS_STREAM, ">",
          ) as RedisStreamReadResponse | null;
          await processEntries(redis, client, fresh);
        }
      } catch (error) {
        logger.warn(`Bingo Redis consumer unavailable; retrying: ${(error as Error).message}`);
        await new Promise((resolve) => setTimeout(resolve, REDIS_RECONNECT_MS));
      }
    }
  })();
}

export function startBingoService(client: Client): void {
  const redis = redisClient();
  let reconciling = false;
  let seeded = false;
  const reconcile = async () => {
    if (reconciling) return;
    reconciling = true;
    try {
      if (!seeded) {
        for (const card of PREPARED_BINGO_CARDS) await seedBingoCard(card);
        seeded = true;
      }
      await publishActiveCard(redis, client);
      await deliverPendingMilestones(client);
    } catch (error) {
      logger.error("Bingo reconciliation failed:", error);
    } finally {
      reconciling = false;
    }
  };
  void reconcile();
  setInterval(reconcile, RECONCILE_INTERVAL_MS);
  startCompletionConsumer(client);
}
