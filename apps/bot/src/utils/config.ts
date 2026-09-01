import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.resolve(__dirname, "../../config.json");

interface IdConfig {
  guildId: string;
  roles: {
    mod: string;
    council: string;
    punished: string;
    bingoLine?: string;
    bingoBlackout?: string;
    bingoPing?: string;
    live?: string;
    currentSeason?: string;
  };
  redis?: {
    host?: string;
    port?: number;
    password?: string;
    punishmentStream?: string;
    punishmentGroup?: string;
    bingoStream?: string;
    bingoGroup?: string;
    bingoActiveCardKey?: string;
  };
  channels: {
    applicationCategory: string;
    log: string;
    ticketLog?: string;
    leaderboard?: string;
    wiki?: string;
    starboard?: string;
    counting?: string;
    bingo?: string;
    ticketCategory: string;
  };
  gallery?: {
    channels?: Array<{
      channelId: string;
      seasonId: string;
    }>;
  };
  bingo?: {
    ownerUserId?: string;
    missingCardWarningHours?: number;
  };
}

let ids: IdConfig;
try {
  ids = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8")) as IdConfig;
} catch (error) {
  throw new Error(
    `Failed to load config from ${CONFIG_PATH}: ${(error as Error).message}`,
  );
}

const REQUIRED_ENV = [
  "DISCORD_BOT_TOKEN",
  "DISCORD_DATABASE_URL",
  "DATABASE_URL",
] as const;

const missingEnv = REQUIRED_ENV.filter((k) => !process.env[k]);
if (missingEnv.length > 0) {
  throw new Error(
    `Missing required environment variables: ${missingEnv.join(", ")}`,
  );
}

const REQUIRED_IDS: Array<[string, string | undefined]> = [
  ["guildId", ids.guildId],
  ["roles.mod", ids.roles?.mod],
  ["roles.council", ids.roles?.council],
  ["roles.punished", ids.roles?.punished],
  ["channels.applicationCategory", ids.channels?.applicationCategory],
  ["channels.log", ids.channels?.log],
  ["channels.ticketCategory", ids.channels?.ticketCategory],
];
const missingIds = REQUIRED_IDS.filter(([, v]) => !v).map(([k]) => k);
if (missingIds.length > 0) {
  throw new Error(
    `Missing required IDs in ${CONFIG_PATH}: ${missingIds.join(", ")}`,
  );
}

export interface GalleryChannelConfig {
  channelId: string;
  seasonId: string;
}

function parseGalleryChannels(): {
  channels: GalleryChannelConfig[];
  errors: string[];
} {
  const configuredChannels = ids.gallery?.channels;
  const entries = Array.isArray(configuredChannels) ? configuredChannels : [];
  const channels: GalleryChannelConfig[] = [];
  const errors: string[] =
    configuredChannels === undefined || Array.isArray(configuredChannels)
      ? []
      : ["gallery.channels must be an array."];
  const seenChannelIds = new Set<string>();

  for (const [index, entry] of entries.entries()) {
    const channelId = typeof entry?.channelId === "string"
      ? entry.channelId.trim()
      : "";
    const seasonId = typeof entry?.seasonId === "string"
      ? entry.seasonId.trim()
      : "";

    if (!/^\d{17,20}$/.test(channelId)) {
      errors.push(
        `gallery.channels[${index}].channelId must be a Discord channel ID.`,
      );
      continue;
    }
    if (!/^[1-7]$/.test(seasonId)) {
      errors.push(
        `gallery.channels[${index}].seasonId must be a numeric string from \"1\" to \"7\".`,
      );
      continue;
    }
    if (seenChannelIds.has(channelId)) {
      errors.push(`Duplicate gallery channel ID: ${channelId}`);
      continue;
    }

    seenChannelIds.add(channelId);
    channels.push({ channelId, seasonId });
  }
  return { channels, errors };
}

const galleryChannels = parseGalleryChannels();

const DEFAULT_CRABCRAFT_API_URL = "https://api.crabcraft.net";

function normalizeOptionalUrl(name: string, fallback: string): string {
  const raw = process.env[name]?.trim();
  const value = raw && raw.length > 0 ? raw : fallback;
  try {
    return new URL(value).toString().replace(/\/+$/, "");
  } catch {
    throw new Error(`${name} must be a valid URL.`);
  }
}

interface IConfig {
  // Secrets / environment-specific
  DISCORD_BOT_TOKEN: string;
  DISCORD_DATABASE_URL: string;
  DATABASE_URL: string;
  CRABCRAFT_API_URL: string;
  YOUTUBE_API_KEY: string;
  TWITCH_CLIENT_ID: string;
  TWITCH_CLIENT_SECRET: string;
  OPENAI_API_KEY: string;
  GALLERY_S3_ENDPOINT: string;
  GALLERY_S3_ACCESS_KEY_ID: string;
  GALLERY_S3_SECRET_ACCESS_KEY: string;
  GALLERY_S3_BUCKET: string;
  GALLERY_S3_REGION: string;
  GALLERY_MEDIA_BASE_URL: string;
  GALLERY_CLOUDFLARE_ZONE_ID: string;
  GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN: string;
  POSTHOG_PROJECT_TOKEN: string;
  POSTHOG_HOST: string;
  POSTHOG_PERSON_SALT: string;
  POSTHOG_ENVIRONMENT: string;

  // Discord IDs (config.json)
  GUILD_ID: string;
  MOD_ROLE_ID: string;
  COUNCIL_ROLE_ID: string;
  PUNISHED_ROLE_ID: string;
  LIVE_ROLE_ID: string;
  CURRENT_SEASON_ROLE_ID: string;
  REDIS_HOST: string;
  REDIS_PORT: number;
  REDIS_PASSWORD: string;
  PUNISHMENT_REDIS_STREAM: string;
  PUNISHMENT_REDIS_GROUP: string;
  BINGO_REDIS_STREAM: string;
  BINGO_REDIS_GROUP: string;
  BINGO_ACTIVE_CARD_KEY: string;
  APPLICATION_CATEGORY_ID: string;
  LOG_CHANNEL_ID: string;
  TICKET_LOG_CHANNEL_ID: string;
  LEADERBOARD_CHANNEL_ID: string;
  WIKI_CHANNEL_ID: string;
  STARBOARD_CHANNEL_ID: string;
  COUNTING_CHANNEL_ID: string;
  BINGO_CHANNEL_ID: string;
  BINGO_LINE_ROLE_ID: string;
  BINGO_BLACKOUT_ROLE_ID: string;
  BINGO_PING_ROLE_ID: string;
  BINGO_OWNER_USER_ID: string;
  BINGO_MISSING_CARD_WARNING_HOURS: number;
  TICKET_CATEGORY_ID: string;
  GALLERY_CHANNELS: readonly GalleryChannelConfig[];
  GALLERY_CONFIGURATION_ERRORS: readonly string[];
}

const config: IConfig = {
  DISCORD_BOT_TOKEN: process.env.DISCORD_BOT_TOKEN!,
  DISCORD_DATABASE_URL: process.env.DISCORD_DATABASE_URL!,
  DATABASE_URL: process.env.DATABASE_URL!,
  CRABCRAFT_API_URL: normalizeOptionalUrl(
    "CRABCRAFT_API_URL",
    DEFAULT_CRABCRAFT_API_URL,
  ),
  YOUTUBE_API_KEY: process.env.YOUTUBE_API_KEY ?? "",
  TWITCH_CLIENT_ID: process.env.TWITCH_CLIENT_ID ?? "",
  TWITCH_CLIENT_SECRET: process.env.TWITCH_CLIENT_SECRET ?? "",
  OPENAI_API_KEY: process.env.OPENAI_API_KEY ?? "",
  GALLERY_S3_ENDPOINT: process.env.GALLERY_S3_ENDPOINT ?? "",
  GALLERY_S3_ACCESS_KEY_ID: process.env.GALLERY_S3_ACCESS_KEY_ID ?? "",
  GALLERY_S3_SECRET_ACCESS_KEY: process.env.GALLERY_S3_SECRET_ACCESS_KEY ?? "",
  GALLERY_S3_BUCKET: process.env.GALLERY_S3_BUCKET ?? "",
  GALLERY_S3_REGION: process.env.GALLERY_S3_REGION ?? "",
  GALLERY_MEDIA_BASE_URL: process.env.GALLERY_MEDIA_BASE_URL ?? "",
  GALLERY_CLOUDFLARE_ZONE_ID:
    process.env.GALLERY_CLOUDFLARE_ZONE_ID?.trim() ?? "",
  GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN:
    process.env.GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN?.trim() ?? "",
  POSTHOG_PROJECT_TOKEN: process.env.POSTHOG_PROJECT_TOKEN?.trim() ?? "",
  POSTHOG_HOST: normalizeOptionalUrl(
    "POSTHOG_HOST",
    "https://eu.i.posthog.com",
  ),
  POSTHOG_PERSON_SALT: process.env.POSTHOG_PERSON_SALT?.trim() ?? "",
  POSTHOG_ENVIRONMENT:
    process.env.POSTHOG_ENVIRONMENT?.trim() ||
    process.env.NODE_ENV?.trim() ||
    "production",

  GUILD_ID: ids.guildId,
  MOD_ROLE_ID: ids.roles.mod,
  COUNCIL_ROLE_ID: ids.roles.council,
  PUNISHED_ROLE_ID: ids.roles.punished,
  LIVE_ROLE_ID: ids.roles.live ?? "",
  CURRENT_SEASON_ROLE_ID: ids.roles.currentSeason ?? "",
  REDIS_HOST: ids.redis?.host ?? "localhost",
  REDIS_PORT: ids.redis?.port ?? 6379,
  REDIS_PASSWORD: ids.redis?.password ?? "",
  PUNISHMENT_REDIS_STREAM: ids.redis?.punishmentStream ?? "crabcraft:punishments",
  PUNISHMENT_REDIS_GROUP: ids.redis?.punishmentGroup ?? "crabcraft-bot",
  BINGO_REDIS_STREAM: ids.redis?.bingoStream ?? "crabcraft:bingo:completions",
  BINGO_REDIS_GROUP: ids.redis?.bingoGroup ?? "crabcraft-bot-bingo",
  BINGO_ACTIVE_CARD_KEY: ids.redis?.bingoActiveCardKey ?? "crabcraft:bingo:active-card",
  APPLICATION_CATEGORY_ID: ids.channels.applicationCategory,
  LOG_CHANNEL_ID: ids.channels.log,
  // Ticket transcripts go here; falls back to the general log channel if unset.
  TICKET_LOG_CHANNEL_ID: ids.channels.ticketLog || ids.channels.log,
  LEADERBOARD_CHANNEL_ID: ids.channels.leaderboard ?? "",
  WIKI_CHANNEL_ID: ids.channels.wiki ?? "",
  STARBOARD_CHANNEL_ID: ids.channels.starboard ?? "",
  COUNTING_CHANNEL_ID: ids.channels.counting ?? "",
  BINGO_CHANNEL_ID: ids.channels.bingo ?? "",
  BINGO_LINE_ROLE_ID: ids.roles.bingoLine ?? "",
  BINGO_BLACKOUT_ROLE_ID: ids.roles.bingoBlackout ?? "",
  BINGO_PING_ROLE_ID: ids.roles.bingoPing ?? "",
  BINGO_OWNER_USER_ID: ids.bingo?.ownerUserId ?? "",
  BINGO_MISSING_CARD_WARNING_HOURS: Math.min(
    168,
    Math.max(1, ids.bingo?.missingCardWarningHours ?? 48),
  ),
  TICKET_CATEGORY_ID: ids.channels.ticketCategory,
  GALLERY_CHANNELS: galleryChannels.channels,
  GALLERY_CONFIGURATION_ERRORS: galleryChannels.errors,
};

export default config;
