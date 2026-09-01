import { Client, Collection, GatewayIntentBits, Partials } from "discord.js";
import logger from "./utils/logger.js";
import config from "./utils/config.js";

logger.info("Starting Crabby...");

import { loadCommands } from "./handlers/commands.js";
import { loadEvents } from "./handlers/events.js";
import { closePool } from "./utils/database.js";
import { shutdownAnalytics } from "./utils/analytics.js";

import type SlashCommand from "./structures/SlashCommand.js";

export const start = Date.now();

export const client = new Client({
  intents: [
    GatewayIntentBits.GuildMessages,
    GatewayIntentBits.Guilds,
    GatewayIntentBits.MessageContent,
    GatewayIntentBits.GuildMembers,
    GatewayIntentBits.GuildMessageReactions,
  ],
  partials: [
    Partials.Message,
    Partials.Channel,
    Partials.Reaction,
    Partials.User,
  ],
  allowedMentions: {
    parse: ["users"],
  },
});

export const commands: Collection<string, SlashCommand> = new Collection();

(async () => {
  logger.info("Loading commands...");
  await loadCommands();
  logger.info("Loading events...");
  await loadEvents();
  await client.login(config.DISCORD_BOT_TOKEN);
})();

// Graceful shutdown
async function shutdown(signal: string) {
  logger.info(`Received ${signal}, shutting down gracefully...`);
  client.destroy();
  await shutdownAnalytics();
  await closePool();
  process.exit(0);
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

// Handle unhandled promise rejections
process.on("unhandledRejection", (error) => {
  logger.error("Unhandled promise rejection:", error);
});
// Handle uncaught exceptions
process.on("uncaughtException", (error) => {
  logger.error("Uncaught exception:", error);
});
