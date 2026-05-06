import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.resolve(__dirname, "../../config.json");

interface IdConfig {
  roles: {
    member: string;
    mod: string;
    live?: string;
  };
  channels: {
    applicationCategory: string;
    log: string;
    leaderboard?: string;
    wiki?: string;
    starboard?: string;
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
  ["channels.applicationCategory", ids.channels?.applicationCategory],
  ["channels.log", ids.channels?.log],
];
const missingIds = REQUIRED_IDS.filter(([, v]) => !v).map(([k]) => k);
if (missingIds.length > 0) {
  throw new Error(
    `Missing required IDs in ${CONFIG_PATH}: ${missingIds.join(", ")}`,
  );
}

interface IConfig {
  // Secrets / environment-specific
  DISCORD_BOT_TOKEN: string;
  DISCORD_DATABASE_URL: string;
  DATABASE_URL: string;
  YOUTUBE_API_KEY: string;
  TWITCH_CLIENT_ID: string;
  TWITCH_CLIENT_SECRET: string;

  // Discord IDs (config.json)
  MEMBER_ROLE_ID: string;
  MOD_ROLE_ID: string;
  LIVE_ROLE_ID: string;
  APPLICATION_CATEGORY_ID: string;
  LOG_CHANNEL_ID: string;
  LEADERBOARD_CHANNEL_ID: string;
  WIKI_CHANNEL_ID: string;
  STARBOARD_CHANNEL_ID: string;
}

const config: IConfig = {
  DISCORD_BOT_TOKEN: process.env.DISCORD_BOT_TOKEN!,
  DISCORD_DATABASE_URL: process.env.DISCORD_DATABASE_URL!,
  DATABASE_URL: process.env.DATABASE_URL!,
  YOUTUBE_API_KEY: process.env.YOUTUBE_API_KEY ?? "",
  TWITCH_CLIENT_ID: process.env.TWITCH_CLIENT_ID ?? "",
  TWITCH_CLIENT_SECRET: process.env.TWITCH_CLIENT_SECRET ?? "",

  MEMBER_ROLE_ID: ids.roles.member,
  MOD_ROLE_ID: ids.roles.mod,
  LIVE_ROLE_ID: ids.roles.live ?? "",
  APPLICATION_CATEGORY_ID: ids.channels.applicationCategory,
  LOG_CHANNEL_ID: ids.channels.log,
  LEADERBOARD_CHANNEL_ID: ids.channels.leaderboard ?? "",
  WIKI_CHANNEL_ID: ids.channels.wiki ?? "",
  STARBOARD_CHANNEL_ID: ids.channels.starboard ?? "",
};

export default config;
