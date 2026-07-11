import logger from "../utils/logger.js";
import {
  Routes,
  SlashCommandBuilder,
  type Client,
  TextChannel,
  MessageFlags,
} from "discord.js";
import Event from "../structures/Event.js";
import { start } from "../index.js";
import { commands } from "../index.js";
import config from "../utils/config.js";
import { loadLeaderboardState } from "../utils/leaderboardState.js";
import {
  fetchLeaderboardData,
  buildLeaderboardComponents,
  DEFAULT_LEADERBOARD_SEASON,
} from "../utils/leaderboard.js";
import {
  LEADERBOARD_REFRESH_MS,
  APPLICATION_REMINDER_DELAY_MS,
  APPLICATION_REMINDER_CHECK_MS,
  APPLICATION_INACTIVE_DELETE_MS,
  TICKET_CLEANUP_INTERVAL_MS,
} from "../utils/constants.js";
import { syncLeaderboardEmojis } from "../utils/playerEmoji.js";
import * as appDb from "../utils/appDb.js";
import { primaryContainer } from "../utils/embeds.js";
import {
  backfillApplicationChannels,
  buildApplyButton,
  finalizeApplicationChannel,
} from "../utils/applicationChannel.js";
import { cleanupExpiredTicket } from "../utils/ticketCleanup.js";
import { initWikiPoller } from "../utils/wiki.js";
import { initStreamMonitor } from "../utils/streamMonitor.js";
import { startIdentitySync } from "../utils/identitySync.js";
import { startPunishmentRoleSync } from "../utils/punishmentRoleSync.js";
import { startBotPlayerStatus } from "../utils/botStatus.js";

export default class ReadyEvent extends Event {
  constructor() {
    super("BotReady", "clientReady", true);
  }

  async execute(client: Client) {
    logger.info(
      `${client.user?.tag} logged into Discord in ${Date.now() - start}ms`,
    );

    // One-time migration: adopt any pre-existing topic-based application
    // channels into the application_channels table so in-flight applications
    // keep working after this deploy.
    try {
      for (const [, guild] of client.guilds.cache) {
        await backfillApplicationChannels(guild);
      }
    } catch (error) {
      logger.error("Error backfilling application channels:", error);
    }

    // Application channel cleanup — delete channels past their post-decision
    // deletion window. Runs on startup and on an interval, so a restart that
    // loses the in-process timer can't orphan a channel.
    const cleanupExpiredApplicationChannels = async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        const expired = await appDb.getExpiredApplicationChannels(now);
        for (const row of expired) {
          const guild = await client.guilds
            .fetch(row.guild_id)
            .catch(() => null);
          if (!guild) {
            await appDb.deleteApplicationChannelRow(row.channel_id).catch(() => null);
            continue;
          }
          await finalizeApplicationChannel(guild, row, null);
        }
      } catch (error) {
        logger.error("Application channel cleanup scan failed:", error);
      }
    };
    await cleanupExpiredApplicationChannels();
    setInterval(cleanupExpiredApplicationChannels, TICKET_CLEANUP_INTERVAL_MS);

    // Start leaderboard update loop
    setInterval(
      async () => {
        try {
          const state = await loadLeaderboardState();
          if (!state.channelId || !state.messageId) return;

          const channel = await client.channels
            .fetch(state.channelId)
            .catch(() => null) as TextChannel | null;
          if (!channel) return;

          const season = state.season ?? DEFAULT_LEADERBOARD_SEASON;
          const data = await fetchLeaderboardData(season);
          if (!data) return;

          const emojiMap = await syncLeaderboardEmojis(client, data.topPlayers);

          const message = await channel.messages
            .fetch(state.messageId)
            .catch(() => null);
          if (!message) return;

          await message.edit({
            components: buildLeaderboardComponents(data, emojiMap, season),
            flags: MessageFlags.IsComponentsV2,
          });
        } catch (error) {
          logger.error("Error updating leaderboard in background:", error);
        }
      },
      LEADERBOARD_REFRESH_MS,
    );

    // Application reminder scan — nudge applicants who opened a channel but
    // haven't submitted an application after the reminder delay.
    const scanApplicationReminders = async () => {
      try {
        const createdBefore = Math.floor(
          (Date.now() - APPLICATION_REMINDER_DELAY_MS) / 1000,
        );
        const due = await appDb.getApplicationChannelsNeedingReminder(createdBefore);

        for (const row of due) {
          // Skip if the applicant already submitted an application.
          const hasPending = await appDb.hasPendingApplication(row.applicant_id);
          if (hasPending) continue;

          const channel = await client.channels
            .fetch(row.channel_id)
            .catch(() => null) as TextChannel | null;
          if (!channel) {
            // Channel is gone — drop the stale row.
            await appDb.deleteApplicationChannelRow(row.channel_id).catch(() => null);
            continue;
          }

          await channel.send({ content: `<@${row.applicant_id}>` });
          await channel.send({
            components: [
              primaryContainer(
                `## <:Crab:1397355651822256299> Reminder!\nYou haven't submitted your application yet! If you have any questions, feel free to ask in this channel.\n\nOtherwise, click the button below to get started.`,
              ),
              buildApplyButton(),
            ],
            flags: MessageFlags.IsComponentsV2,
          });

          await appDb.markApplicationChannelReminded(row.channel_id);
        }
      } catch (error) {
        logger.error("Error during application reminder scan:", error);
      }
    };

    // Run once on startup, then every 30 minutes
    await scanApplicationReminders();
    setInterval(scanApplicationReminders, APPLICATION_REMINDER_CHECK_MS);

    // Inactive application cleanup — delete the channel (but keep the member)
    // when no application has been submitted within the inactivity window.
    // They can reopen one anytime from the application hub.
    const cleanupInactiveApplicationChannels = async () => {
      try {
        const createdBefore = Math.floor(
          (Date.now() - APPLICATION_INACTIVE_DELETE_MS) / 1000,
        );
        const stale = await appDb.getApplicationChannelsOlderThan(createdBefore);
        for (const row of stale) {
          // Keep channels for applicants who actually submitted (awaiting review).
          const hasPending = await appDb.hasPendingApplication(row.applicant_id);
          if (hasPending) continue;

          const guild = await client.guilds
            .fetch(row.guild_id)
            .catch(() => null);
          if (!guild) {
            await appDb.deleteApplicationChannelRow(row.channel_id).catch(() => null);
            continue;
          }
          await finalizeApplicationChannel(guild, row, null);
        }
      } catch (error) {
        logger.error("Inactive application cleanup scan failed:", error);
      }
    };
    await cleanupInactiveApplicationChannels();
    setInterval(cleanupInactiveApplicationChannels, APPLICATION_REMINDER_CHECK_MS);

    // Ticket cleanup — delete closed-ticket channels past their delete window
    const cleanupExpiredTickets = async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        const expired = await appDb.getExpiredClosedTickets(now);
        for (const ticket of expired) {
          await cleanupExpiredTicket(client, ticket.id, now);
        }
      } catch (e) {
        logger.error("Ticket cleanup scan failed:", e);
      }
    };
    await cleanupExpiredTickets();
    setInterval(cleanupExpiredTickets, TICKET_CLEANUP_INTERVAL_MS);

    // Wiki recent changes poller
    await initWikiPoller(client);

    // Keep mutable Discord/Minecraft usernames current.
    startIdentitySync(client);

    // Keep the Discord punishment role aligned with active Minecraft bans/mutes.
    startPunishmentRoleSync(client);

    // Show the current online player count in the bot status.
    startBotPlayerStatus(client, config.CRABCRAFT_API_URL);

    // Deploy slash commands on every startup
    if (!client.user) return;

    const commandData: any[] = [];
    await Promise.all(
      commands.map(async (command) => {
        commandData.push(
          await command.build(
            new SlashCommandBuilder()
              .setName(command.name)
              .setDescription(command.description),
          ),
        );
      }),
    );

    try {
      await client.rest.put(
        Routes.applicationCommands(client.user.id),
        { body: commandData },
      );
      logger.debug("Deployed slash commands globally");
    } catch (error) {
      logger.error("Failed to deploy slash commands:", error);
    }

    // Start stream monitor
    await initStreamMonitor(client);
  }
}
