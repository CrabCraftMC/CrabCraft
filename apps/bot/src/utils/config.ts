import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.resolve(__dirname, "../../config.json");

interface IdConfig {
  roles: {
    member: string;
    mod: string;
    punished: string;
    live?: string;
    currentSeason?: string;
  };
  redis?: {
    host?: string;
    port?: number;
    password?: string;
    punishmentStream?: string;
    punishmentGroup?: string;
  };
  channels: {
    applicationCategory: string;
    log: string;
    ticketLog?: string;
    leaderboard?: string;
    wiki?: string;
    starboard?: string;
    counting?: string;
    ticketCategory: string;
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
  ["roles.member", ids.roles?.member],
  ["roles.mod", ids.roles?.mod],
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

  // Discord IDs (config.json)
  MEMBER_ROLE_ID: string;
  MOD_ROLE_ID: string;
  PUNISHED_ROLE_ID: string;
  LIVE_ROLE_ID: string;
  CURRENT_SEASON_ROLE_ID: string;
  REDIS_HOST: string;
  REDIS_PORT: number;
  REDIS_PASSWORD: string;
  PUNISHMENT_REDIS_STREAM: string;
  PUNISHMENT_REDIS_GROUP: string;
  APPLICATION_CATEGORY_ID: string;
  LOG_CHANNEL_ID: string;
  TICKET_LOG_CHANNEL_ID: string;
  LEADERBOARD_CHANNEL_ID: string;
  WIKI_CHANNEL_ID: string;
  STARBOARD_CHANNEL_ID: string;
  COUNTING_CHANNEL_ID: string;
  TICKET_CATEGORY_ID: string;
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

  MEMBER_ROLE_ID: ids.roles.member,
  MOD_ROLE_ID: ids.roles.mod,
  PUNISHED_ROLE_ID: ids.roles.punished,
  LIVE_ROLE_ID: ids.roles.live ?? "",
  CURRENT_SEASON_ROLE_ID: ids.roles.currentSeason ?? "",
  REDIS_HOST: ids.redis?.host ?? "localhost",
  REDIS_PORT: ids.redis?.port ?? 6379,
  REDIS_PASSWORD: ids.redis?.password ?? "",
  PUNISHMENT_REDIS_STREAM: ids.redis?.punishmentStream ?? "crabcraft:punishments",
  PUNISHMENT_REDIS_GROUP: ids.redis?.punishmentGroup ?? "crabcraft-bot",
  APPLICATION_CATEGORY_ID: ids.channels.applicationCategory,
  LOG_CHANNEL_ID: ids.channels.log,
  // Ticket transcripts go here; falls back to the general log channel if unset.
  TICKET_LOG_CHANNEL_ID: ids.channels.ticketLog || ids.channels.log,
  LEADERBOARD_CHANNEL_ID: ids.channels.leaderboard ?? "",
  WIKI_CHANNEL_ID: ids.channels.wiki ?? "",
  STARBOARD_CHANNEL_ID: ids.channels.starboard ?? "",
  COUNTING_CHANNEL_ID: ids.channels.counting ?? "",
  TICKET_CATEGORY_ID: ids.channels.ticketCategory,
};

export default config;
