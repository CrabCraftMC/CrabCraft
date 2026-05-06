const REQUIRED_KEYS = [
  "DISCORD_BOT_TOKEN",
  "MEMBER_ROLE_ID", "MOD_ROLE_ID", "APPLICATION_CATEGORY_ID", "LOG_CHANNEL_ID",
  "DISCORD_DATABASE_URL",
  "DATABASE_URL",
] as const;

const missing = REQUIRED_KEYS.filter((k) => !process.env[k]);
if (missing.length > 0) {
  throw new Error(`Missing required environment variables: ${missing.join(", ")}`);
}

interface IConfig {
  DISCORD_BOT_TOKEN: string;
  MEMBER_ROLE_ID: string;
  MOD_ROLE_ID: string;
  APPLICATION_CATEGORY_ID: string;
  LOG_CHANNEL_ID: string;

  LEADERBOARD_CHANNEL_ID: string;
  WIKI_CHANNEL_ID: string;
  STARBOARD_CHANNEL_ID: string;

  DISCORD_DATABASE_URL: string;
  DATABASE_URL: string;

  YOUTUBE_API_KEY: string;
  TWITCH_CLIENT_ID: string;
  TWITCH_CLIENT_SECRET: string;
  LIVE_ROLE_ID: string;
}

const handler = {
  get: function (_: IConfig, name: string) {
    return process.env[name];
  },
};

const config = new Proxy({} as IConfig, handler);

export default config;
