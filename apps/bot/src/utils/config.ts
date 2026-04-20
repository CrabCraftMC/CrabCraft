const REQUIRED_KEYS = [
  "TOKEN", "ENV",
  "MEMBER_ROLE_ID", "MOD_ROLE_ID", "APPLICATION_CATEGORY_ID", "LOG_CHANNEL_ID",
  "DB_HOST", "DB_PORT", "DB_USER", "DB_PASS", "DB_NAME",
  "DATABASE_URL",
] as const;

const missing = REQUIRED_KEYS.filter((k) => !process.env[k]);
if (missing.length > 0) {
  throw new Error(`Missing required environment variables: ${missing.join(", ")}`);
}

interface IConfig {
  ENV: "development" | "production";
  TOKEN: string;
  DEV_GUILD_ID: string;

  MEMBER_ROLE_ID: string;
  MOD_ROLE_ID: string;
  APPLICATION_CATEGORY_ID: string;
  LOG_CHANNEL_ID: string;

  LEADERBOARD_CHANNEL_ID: string;
  WIKI_CHANNEL_ID: string;

  DB_HOST: string;
  DB_PORT: number;
  DB_USER: string;
  DB_PASS: string;
  DB_NAME: string;

  DATABASE_URL: string;
}

const handler = {
  get: function (_: IConfig, name: string) {
    return process.env[name];
  },
};

const config = new Proxy({} as IConfig, handler);

export default config;
