import logger from "../utils/logger.js";
import {
  ChannelType,
  Routes,
  SlashCommandBuilder,
  type Client,
  TextChannel,
  MessageFlags,
} from "discord.js";
import Event from "../structures/Event.js";
import { start } from "../index.js";
import config from "../utils/config.js";
import { saveTranscriptToLog } from "../utils/transcript.js";
import { commands } from "../index.js";
import { loadLeaderboardState } from "../utils/leaderboardState.js";
import {
  fetchLeaderboardData,
  buildLeaderboardComponents,
} from "../utils/leaderboard.js";
import { LEADERBOARD_REFRESH_MS, APPLICATION_REMINDER_DELAY_MS, APPLICATION_REMINDER_CHECK_MS } from "../utils/constants.js";
import { syncLeaderboardEmojis } from "../utils/playerEmoji.js";
import * as appDb from "../utils/appDb.js";
import { primaryContainer } from "../utils/embeds.js";
import { ActionRowBuilder, ButtonBuilder, ButtonStyle } from "discord.js";
import { initWikiPoller } from "../utils/wiki.js";

export default class ReadyEvent extends Event {
  constructor() {
    super("BotReady", "clientReady", true);
  }

  async execute(client: Client) {
    logger.info(
      `${client.user?.tag} logged into Discord in ${Date.now() - start}ms`,
    );

    // Clean up application channels that were scheduled for deletion before a restart
    try {
      const category = client.channels.cache.get(config.APPLICATION_CATEGORY_ID);
      if (category?.type === ChannelType.GuildCategory) {
        for (const [, ch] of category.children.cache) {
          const topic = (ch as TextChannel).topic ?? "";
          const match = topic.match(/\|delete-after:(\d+)/);
          if (match && Date.now() >= Number(match[1])) {
            try {
              const logCh = await (ch as TextChannel).guild.channels
                .fetch(config.LOG_CHANNEL_ID)
                .catch(() => null) as TextChannel | null;
              if (logCh) {
                await saveTranscriptToLog(ch as TextChannel, logCh, "startup cleanup").catch(() => null);
              }
            } catch { /* don't block cleanup */ }
            await ch.delete().catch((e) => logger.error("Startup channel cleanup failed:", e));
          }
        }
      }
    } catch (error) {
      logger.error("Error during startup channel cleanup:", error);
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

    // Application reminder scan - sends a reminder to inactive applicants
    const scanApplicationReminders = async () => {
      try {
        const appCategory = client.channels.cache.get(config.APPLICATION_CATEGORY_ID);
        if (!appCategory || appCategory.type !== ChannelType.GuildCategory) return;

        for (const [, ch] of appCategory.children.cache) {
          if (!ch.isTextBased()) continue;
          const textChannel = ch as TextChannel;
          const topic = textChannel.topic;
          if (!topic) continue;

          const parts = topic.split("|");
          const userId = parts[0];

          // Skip if already reminded or scheduled for deletion
          if (parts.some((p) => p === "reminded" || p.startsWith("delete-after:"))) continue;

          // Use Discord's built-in channel creation time
          if (Date.now() - ch.createdTimestamp < APPLICATION_REMINDER_DELAY_MS) continue;

          // Check if user already has a pending application
          const hasPending = await appDb.hasPendingApplication(userId);
          if (hasPending) continue;

          // Send reminder
          const applyButton = new ActionRowBuilder<ButtonBuilder>().addComponents(
            new ButtonBuilder()
              .setCustomId("apply")
              .setLabel("Apply")
              .setStyle(ButtonStyle.Primary)
              .setEmoji("📝"),
          );

          await textChannel.send({
            content: `<@${userId}>`,
          });
          await textChannel.send({
            components: [
              primaryContainer(
                `## <:Crab:1397355651822256299> Reminder!\nYou haven't submitted your application yet! If you have any questions, feel free to ask in this channel.\n\nOtherwise, click the button below to get started.`,
              ),
              applyButton,
            ],
            flags: MessageFlags.IsComponentsV2,
          });

          // Mark as reminded
          await textChannel.setTopic(`${topic}|reminded`).catch(() => null);
        }
      } catch (error) {
        logger.error("Error during application reminder scan:", error);
      }
    };

    // Run once on startup, then every 30 minutes
    await scanApplicationReminders();
    setInterval(scanApplicationReminders, APPLICATION_REMINDER_CHECK_MS);

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
      if (config.ENV === "development" && config.DEV_GUILD_ID) {
        await client.rest.put(
          Routes.applicationGuildCommands(client.user.id, config.DEV_GUILD_ID),
          { body: commandData },
        );
        logger.debug("Deployed slash commands to dev guild");
      } else {
        await client.rest.put(
          Routes.applicationCommands(client.user.id),
          { body: commandData },
        );
        logger.debug("Deployed slash commands globally");
      }
    } catch (error) {
      logger.error("Failed to deploy slash commands:", error);
    }
  }
}
