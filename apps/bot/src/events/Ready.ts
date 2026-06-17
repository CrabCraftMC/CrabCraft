import logger from "../utils/logger.js";
import {
  Routes,
  SlashCommandBuilder,
  type Client,
  TextChannel,
  ThreadChannel,
  MessageFlags,
} from "discord.js";
import Event from "../structures/Event.js";
import { start } from "../index.js";
import { commands } from "../index.js";
import { loadLeaderboardState } from "../utils/leaderboardState.js";
import {
  fetchLeaderboardData,
  buildLeaderboardComponents,
} from "../utils/leaderboard.js";
import {
  LEADERBOARD_REFRESH_MS,
  APPLICATION_REMINDER_DELAY_MS,
  APPLICATION_REMINDER_CHECK_MS,
  TICKET_CLEANUP_INTERVAL_MS,
} from "../utils/constants.js";
import { syncLeaderboardEmojis } from "../utils/playerEmoji.js";
import * as appDb from "../utils/appDb.js";
import { primaryContainer } from "../utils/embeds.js";
import {
  buildApplyButton,
  ensureApplicationChannelPermissions,
  finalizeApplicationThread,
} from "../utils/applicationThread.js";
import { initWikiPoller } from "../utils/wiki.js";
import { initStreamMonitor } from "../utils/streamMonitor.js";

export default class ReadyEvent extends Event {
  constructor() {
    super("BotReady", "clientReady", true);
  }

  async execute(client: Client) {
    logger.info(
      `${client.user?.tag} logged into Discord in ${Date.now() - start}ms`,
    );

    // Ensure the moderator role can see + manage the application channel and
    // its private threads on every startup (idempotent).
    try {
      for (const [, guild] of client.guilds.cache) {
        await ensureApplicationChannelPermissions(guild);
      }
    } catch (error) {
      logger.error("Error ensuring application channel permissions:", error);
    }

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

          const data = await fetchLeaderboardData();
          if (!data) return;

          const emojiMap = await syncLeaderboardEmojis(client, data.topPlayers);

          const message = await channel.messages
            .fetch(state.messageId)
            .catch(() => null);
          if (!message) return;

          await message.edit({
            components: buildLeaderboardComponents(data, emojiMap),
            flags: MessageFlags.IsComponentsV2,
          });
        } catch (error) {
          logger.error("Error updating leaderboard in background:", error);
        }
      },
      LEADERBOARD_REFRESH_MS,
    );

    // Application reminder scan — nudge applicants who opened a thread but
    // haven't submitted an application after the reminder delay.
    const scanApplicationReminders = async () => {
      try {
        const createdBefore = Math.floor(
          (Date.now() - APPLICATION_REMINDER_DELAY_MS) / 1000,
        );
        const due = await appDb.getApplicationThreadsNeedingReminder(createdBefore);

        for (const row of due) {
          // Skip if the applicant already submitted an application.
          const hasPending = await appDb.hasPendingApplication(row.applicant_id);
          if (hasPending) continue;

          const thread = await client.channels
            .fetch(row.thread_id)
            .catch(() => null) as ThreadChannel | null;
          if (!thread) {
            // Thread is gone — drop the stale row.
            await appDb.deleteApplicationThreadRow(row.thread_id).catch(() => null);
            continue;
          }
          if (thread.archived) await thread.setArchived(false).catch(() => null);

          await thread.send({ content: `<@${row.applicant_id}>` });
          await thread.send({
            components: [
              primaryContainer(
                `## <:Crab:1397355651822256299> Reminder!\nYou haven't submitted your application yet! If you have any questions, feel free to ask in this thread.\n\nOtherwise, click the button below to get started.`,
              ),
              buildApplyButton(),
            ],
            flags: MessageFlags.IsComponentsV2,
          });

          await appDb.markApplicationThreadReminded(row.thread_id);
        }
      } catch (error) {
        logger.error("Error during application reminder scan:", error);
      }
    };

    // Run once on startup, then every 30 minutes
    await scanApplicationReminders();
    setInterval(scanApplicationReminders, APPLICATION_REMINDER_CHECK_MS);

    // Application thread cleanup — delete threads past their post-decision
    // deletion window (restart-safe; survives lost in-process timers).
    const cleanupExpiredApplicationThreads = async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        const expired = await appDb.getExpiredApplicationThreads(now);
        for (const row of expired) {
          const guild = await client.guilds
            .fetch(row.guild_id)
            .catch(() => null);
          if (!guild) {
            await appDb.deleteApplicationThreadRow(row.thread_id).catch(() => null);
            continue;
          }
          await finalizeApplicationThread(guild, row, null);
        }
      } catch (error) {
        logger.error("Application thread cleanup scan failed:", error);
      }
    };
    await cleanupExpiredApplicationThreads();
    setInterval(cleanupExpiredApplicationThreads, TICKET_CLEANUP_INTERVAL_MS);

    // Ticket cleanup — delete closed-ticket channels past their delete window
    const cleanupExpiredTickets = async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        const expired = await appDb.getExpiredClosedTickets(now);
        for (const ticket of expired) {
          try {
            const channel = await client.channels
              .fetch(ticket.channel_id)
              .catch(() => null);
            if (channel) {
              await channel.delete(`Ticket #${ticket.id} expired`).catch(() => null);
            }
          } catch (e) {
            logger.error(`Ticket cleanup: failed to delete channel for #${ticket.id}:`, e);
          }
          try {
            await appDb.deleteTicketRow(ticket.id);
          } catch (e) {
            logger.error(`Ticket cleanup: failed to delete row for #${ticket.id}:`, e);
          }
        }
      } catch (e) {
        logger.error("Ticket cleanup scan failed:", e);
      }
    };
    await cleanupExpiredTickets();
    setInterval(cleanupExpiredTickets, TICKET_CLEANUP_INTERVAL_MS);

    // Wiki recent changes poller
    await initWikiPoller(client);

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
