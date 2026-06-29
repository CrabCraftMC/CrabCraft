import type { Client, Guild } from "discord.js";
import Redis from "ioredis";
import * as appDb from "./appDb.js";
import config from "./config.js";
import { PUNISHMENT_ROLE_SYNC_INTERVAL_MS } from "./constants.js";
import logger from "./logger.js";
import {
  normalizeMinecraftUuidKey,
  planPunishmentRoleChanges,
  type LinkedPunishmentAccount,
} from "./punishmentRoleSyncPlan.js";

const ACTIVE_PUNISHMENT_BATCH_SIZE = 1000;
const ACTIVE_PUNISHMENT_TIMEOUT_MS = 10_000;
const REDIS_READ_BLOCK_MS = 10_000;
const REDIS_RECONNECT_DELAY_MS = 3_000;

interface RoleApplyStats {
  added: number;
  removed: number;
  failed: number;
}

interface AccountCache {
  accounts: LinkedPunishmentAccount[];
  byUuid: Map<string, Set<string>>;
  byDiscordId: Map<string, LinkedPunishmentAccount[]>;
}

interface PunishmentStreamEvent {
  id: string;
  uuid: string;
  active: boolean;
}

export function startPunishmentRoleSync(client: Client): void {
  const guild = client.guilds.cache.first();
  if (!guild) {
    logger.error("Punishment role sync: no guild found");
    return;
  }

  let currentRoleHolders: Set<string> | null = null;
  let accountCache: AccountCache | null = null;
  let operationQueue = Promise.resolve();

  const enqueue = (name: string, task: () => Promise<void>): Promise<void> => {
    const run = operationQueue
      .then(task, task)
      .catch((error) => {
        logger.error(`Punishment role sync ${name} failed:`, error);
        throw error;
      });
    operationQueue = run.catch(() => {});
    return run;
  };

  const ensureRole = async (): Promise<boolean> => {
    const role = await guild.roles.fetch(config.PUNISHED_ROLE_ID).catch(() => null);
    if (!role) {
      logger.error("Punishment role sync: configured role was not found");
      return false;
    }
    return true;
  };

  const ensureRoleHolders = async (): Promise<Set<string>> => {
    if (!currentRoleHolders) {
      currentRoleHolders = await fetchCurrentRoleHolders(guild);
    }
    return currentRoleHolders;
  };

  const loadAccounts = async (): Promise<AccountCache> => {
    accountCache = buildAccountCache(
      (await appDb.getPunishmentRoleSyncAccounts()).map(
        (account): LinkedPunishmentAccount => ({
          discordId: account.discord_id,
          minecraftUuid: account.minecraft_uuid,
        }),
      ),
    );
    return accountCache;
  };

  const reconcile = async () => {
    if (!(await ensureRole())) return;
    const holders = await ensureRoleHolders();
    const accounts = await loadAccounts();
    const uniqueUuids = Array.from(
      new Set(accounts.accounts.map((account) => account.minecraftUuid)),
    );
    const punishedUuids = await fetchActivePunishedUuids(uniqueUuids);
    const plan = planPunishmentRoleChanges(
      accounts.accounts,
      punishedUuids,
      holders,
    );
    const stats = await applyRoleChanges(guild, plan, holders);

    logger.info(
      `Punishment role reconcile complete: ${stats.added}/${plan.add.length} added, ${stats.removed}/${plan.remove.length} removed, ${stats.failed} failed`,
    );
  };

  const reconcileDiscordIds = async (
    accounts: AccountCache,
    discordIds: Iterable<string>,
    reason: string,
  ) => {
    const holders = await ensureRoleHolders();
    const affectedDiscordIds = new Set(discordIds);
    const affectedAccounts = Array.from(affectedDiscordIds).flatMap(
      (discordId) => accounts.byDiscordId.get(discordId) ?? [],
    );
    const uniqueUuids = Array.from(
      new Set(affectedAccounts.map((account) => account.minecraftUuid)),
    );
    const punishedUuids = await fetchActivePunishedUuids(uniqueUuids);
    const scopedRoleHolders = Array.from(affectedDiscordIds).filter((discordId) =>
      holders.has(discordId),
    );
    const plan = planPunishmentRoleChanges(
      affectedAccounts,
      punishedUuids,
      scopedRoleHolders,
    );
    const stats = await applyRoleChanges(guild, plan, holders);

    logger.info(
      `Punishment role ${reason}: ${stats.added}/${plan.add.length} added, ${stats.removed}/${plan.remove.length} removed, ${stats.failed} failed`,
    );
  };

  const handleStreamEvent = async (event: PunishmentStreamEvent) => {
    if (!(await ensureRole())) return;
    const holders = await ensureRoleHolders();
    let accounts = accountCache ?? await loadAccounts();
    const uuidKey = normalizeMinecraftUuidKey(event.uuid);
    let discordIds = accounts.byUuid.get(uuidKey);

    if (!discordIds) {
      accounts = await loadAccounts();
      discordIds = accounts.byUuid.get(uuidKey);
    }

    if (!discordIds || discordIds.size === 0) return;

    if (event.active) {
      const stats = await applyRoleChanges(
        guild,
        { add: Array.from(discordIds), remove: [] },
        holders,
      );
      logger.info(
        `Punishment role stream event ${event.id}: ${stats.added}/${discordIds.size} role additions applied for ${event.uuid}`,
      );
      return;
    }

    const affectedDiscordIds = new Set(discordIds);
    accounts = await loadAccounts();
    for (const discordId of accounts.byUuid.get(uuidKey) ?? []) {
      affectedDiscordIds.add(discordId);
    }
    await reconcileDiscordIds(
      accounts,
      affectedDiscordIds,
      `stream event ${event.id}`,
    );
  };

  void enqueue("initial reconcile", reconcile);
  setInterval(
    () => void enqueue("scheduled reconcile", reconcile),
    PUNISHMENT_ROLE_SYNC_INTERVAL_MS,
  );
  startPunishmentRedisConsumer((event) =>
    enqueue(`stream event ${event.id}`, () => handleStreamEvent(event)),
  );
}

function buildAccountCache(accounts: LinkedPunishmentAccount[]): AccountCache {
  const byUuid = new Map<string, Set<string>>();
  const byDiscordId = new Map<string, LinkedPunishmentAccount[]>();
  for (const account of accounts) {
    const key = normalizeMinecraftUuidKey(account.minecraftUuid);
    let discordIds = byUuid.get(key);
    if (!discordIds) {
      discordIds = new Set();
      byUuid.set(key, discordIds);
    }
    discordIds.add(account.discordId);

    const discordAccounts = byDiscordId.get(account.discordId);
    if (discordAccounts) {
      discordAccounts.push(account);
    } else {
      byDiscordId.set(account.discordId, [account]);
    }
  }
  return { accounts, byUuid, byDiscordId };
}

async function fetchCurrentRoleHolders(guild: Guild): Promise<Set<string>> {
  const members = await guild.members.fetch();
  const holders = new Set<string>();
  for (const member of members.values()) {
    if (member.roles.cache.has(config.PUNISHED_ROLE_ID)) {
      holders.add(member.id);
    }
  }
  return holders;
}

async function fetchActivePunishedUuids(uuids: string[]): Promise<Set<string>> {
  const punishedUuids = new Set<string>();
  for (let i = 0; i < uuids.length; i += ACTIVE_PUNISHMENT_BATCH_SIZE) {
    const batch = uuids.slice(i, i + ACTIVE_PUNISHMENT_BATCH_SIZE);
    const punishedBatch = await fetchActivePunishedUuidBatch(batch);
    for (const uuid of punishedBatch) {
      punishedUuids.add(uuid);
    }
  }
  return punishedUuids;
}

async function fetchActivePunishedUuidBatch(uuids: string[]): Promise<Set<string>> {
  if (uuids.length === 0) return new Set();

  const ctrl = new AbortController();
  const timeout = setTimeout(() => ctrl.abort(), ACTIVE_PUNISHMENT_TIMEOUT_MS);
  try {
    const response = await fetch(`${config.CRABCRAFT_API_URL}/punishments/active`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ uuids }),
      signal: ctrl.signal,
    });
    if (!response.ok) {
      throw new Error(`active punishment lookup failed (${response.status})`);
    }

    return normalizeActivePunishmentsResponse(await response.json());
  } finally {
    clearTimeout(timeout);
  }
}

function normalizeActivePunishmentsResponse(body: unknown): Set<string> {
  if (!isRecord(body) || !Array.isArray(body.punished_uuids)) {
    throw new Error("active punishment lookup returned malformed JSON");
  }

  const punishedUuids = new Set<string>();
  for (const uuid of body.punished_uuids) {
    if (typeof uuid !== "string") {
      throw new Error("active punishment lookup returned a malformed UUID");
    }
    punishedUuids.add(uuid);
  }
  return punishedUuids;
}

function startPunishmentRedisConsumer(
  onEvent: (event: PunishmentStreamEvent) => Promise<void>,
): void {
  const redis = new Redis({
    host: config.REDIS_HOST,
    port: config.REDIS_PORT,
    password: config.REDIS_PASSWORD || undefined,
    lazyConnect: true,
    maxRetriesPerRequest: null,
  });
  const stream = config.PUNISHMENT_REDIS_STREAM;
  const group = config.PUNISHMENT_REDIS_GROUP;
  const consumer = `bot-${process.pid}-${Date.now()}`;

  void (async () => {
    while (true) {
      try {
        if (redis.status === "wait") {
          await redis.connect();
        }
        await ensureRedisConsumerGroup(redis, stream, group);
        logger.info(`Punishment role sync consuming Redis stream ${stream} as ${group}/${consumer}`);

        while (true) {
          const pending = await readPunishmentStreamEntries(
            redis,
            stream,
            group,
            consumer,
            "0",
          );
          if (pending && (await processPunishmentStreamEntries(redis, stream, group, pending, onEvent))) {
            continue;
          }

          const response = await (redis.xreadgroup as (...args: unknown[]) => Promise<unknown>)(
            "GROUP",
            group,
            consumer,
            "BLOCK",
            REDIS_READ_BLOCK_MS,
            "COUNT",
            25,
            "STREAMS",
            stream,
            ">",
          );
          if (!response) continue;

          await processPunishmentStreamEntries(
            redis,
            stream,
            group,
            response as RedisStreamReadResponse,
            onEvent,
          );
        }
      } catch (error) {
        logger.warn(
          `Punishment role Redis consumer unavailable; reconnecting in ${REDIS_RECONNECT_DELAY_MS / 1000}s: ${(error as Error).message}`,
        );
        await delay(REDIS_RECONNECT_DELAY_MS);
      }
    }
  })();
}

type RedisStreamReadResponse = Array<[string, Array<[string, string[]]>]>;

async function readPunishmentStreamEntries(
  redis: Redis,
  stream: string,
  group: string,
  consumer: string,
  id: string,
): Promise<RedisStreamReadResponse | null> {
  const response = await (redis.xreadgroup as (...args: unknown[]) => Promise<unknown>)(
    "GROUP",
    group,
    consumer,
    "COUNT",
    25,
    "STREAMS",
    stream,
    id,
  );
  return response as RedisStreamReadResponse | null;
}

async function processPunishmentStreamEntries(
  redis: Redis,
  stream: string,
  group: string,
  response: RedisStreamReadResponse,
  onEvent: (event: PunishmentStreamEvent) => Promise<void>,
): Promise<boolean> {
  let processed = false;
  for (const [, entries] of response) {
    for (const [id, rawFields] of entries) {
      processed = true;
      const event = parsePunishmentStreamEvent(id, rawFields);
      if (event) {
        await onEvent(event);
      }
      await redis.xack(stream, group, id);
    }
  }
  return processed;
}

async function ensureRedisConsumerGroup(
  redis: Redis,
  stream: string,
  group: string,
): Promise<void> {
  try {
    await redis.xgroup("CREATE", stream, group, "$", "MKSTREAM");
  } catch (error) {
    const message = (error as Error).message;
    if (!message.includes("BUSYGROUP")) {
      throw error;
    }
  }
}

function parsePunishmentStreamEvent(
  id: string,
  rawFields: string[],
): PunishmentStreamEvent | null {
  const fields: Record<string, string> = {};
  for (let i = 0; i < rawFields.length - 1; i += 2) {
    fields[rawFields[i]] = rawFields[i + 1];
  }

  if (!fields.uuid || !fields.active) {
    logger.warn(`Punishment role Redis event ${id} missing uuid or active field`);
    return null;
  }

  return {
    id,
    uuid: fields.uuid,
    active: fields.active === "true" || fields.active === "1",
  };
}

async function applyRoleChanges(
  guild: Guild,
  plan: { add: string[]; remove: string[] },
  currentRoleHolders: Set<string>,
): Promise<RoleApplyStats> {
  const stats: RoleApplyStats = { added: 0, removed: 0, failed: 0 };

  for (const discordId of plan.add) {
    const member = await guild.members.fetch(discordId).catch(() => null);
    if (!member) continue;
    if (member.roles.cache.has(config.PUNISHED_ROLE_ID)) {
      currentRoleHolders.add(discordId);
      continue;
    }
    try {
      await member.roles.add(config.PUNISHED_ROLE_ID, "Minecraft ban/mute role sync");
      currentRoleHolders.add(discordId);
      stats.added += 1;
    } catch (error) {
      stats.failed += 1;
      logger.error(`Punishment role sync: failed to add role to ${discordId}:`, error);
    }
  }

  for (const discordId of plan.remove) {
    const member = await guild.members.fetch(discordId).catch(() => null);
    if (!member) {
      currentRoleHolders.delete(discordId);
      continue;
    }
    if (!member.roles.cache.has(config.PUNISHED_ROLE_ID)) {
      currentRoleHolders.delete(discordId);
      continue;
    }
    try {
      await member.roles.remove(config.PUNISHED_ROLE_ID, "Minecraft ban/mute role sync");
      currentRoleHolders.delete(discordId);
      stats.removed += 1;
    } catch (error) {
      stats.failed += 1;
      logger.error(`Punishment role sync: failed to remove role from ${discordId}:`, error);
    }
  }

  return stats;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
