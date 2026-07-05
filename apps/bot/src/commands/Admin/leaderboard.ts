import SlashCommand from "../../structures/SlashCommand.js";

import { errorContainer, primaryContainer } from "../../utils/embeds.js";

import {
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  SlashCommandBuilder,
  PermissionFlagsBits,
  TextChannel,
  MessageFlags,
} from "discord.js";

import config from "../../utils/config.js";

import {
  fetchLeaderboardData,
  buildLeaderboardComponents,
  DEFAULT_LEADERBOARD_SEASON,
} from "../../utils/leaderboard.js";

import {
  saveLeaderboardState,
  loadLeaderboardState,
} from "../../utils/leaderboardState.js";
import { syncLeaderboardEmojis } from "../../utils/playerEmoji.js";

export default class LeaderboardCommand extends SlashCommand {
  constructor() {
    super("leaderboard", "Manage the leaderboard system", {
      guildOnly: true,
      cooldown: 10,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.guild) return;

    // Permission check
    const executor = await interaction.guild.members.fetch(interaction.user.id);
    if (!executor.roles.cache.has(config.MOD_ROLE_ID)) {
      await interaction.reply({
        components: [errorContainer("**Missing permissions**")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const sub = interaction.options.getSubcommand(true);

    switch (sub) {
      case "create":
        await this.handleCreate(interaction);
        break;
      case "set":
        await this.handleSet(interaction);
        break;
      case "refresh":
        await this.handleRefresh(interaction);
        break;
      default:
        await interaction.reply({
          components: [errorContainer("Unknown subcommand")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
    }
  }

  private async handleCreate(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const prior = await loadLeaderboardState();
    const season =
      interaction.options.getInteger("season") ??
      prior.season ??
      DEFAULT_LEADERBOARD_SEASON;

    const data = await fetchLeaderboardData(season);
    if (!data) {
      await interaction.editReply({
        components: [errorContainer("Failed to fetch leaderboard data.")],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const channel = interaction.channel as TextChannel;
    const emojiMap = await syncLeaderboardEmojis(interaction.client, data.topPlayers);

    try {
      const message = await channel.send({
        components: buildLeaderboardComponents(data, emojiMap, season),
        flags: MessageFlags.IsComponentsV2,
      });

      await saveLeaderboardState({
        channelId: channel.id,
        messageId: message.id,
        season,
      });

      await interaction.editReply({
        components: [
          primaryContainer(
            `## Leaderboard Created\nLeaderboard message created in <#${channel.id}> showing season ${season}. It will update every 5 minutes.`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
    } catch (error) {
      await interaction.editReply({
        components: [
          errorContainer(
            "Failed to send leaderboard message. Check bot permissions.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
    }
  }

  private async handleSet(interaction: ChatInputCommandInteraction) {
    const messageId = interaction.options.getString("message_id", true);
    const channel =
      (interaction.options.getChannel("channel") as TextChannel) ||
      (interaction.channel as TextChannel);

    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    try {
      const message = await channel.messages.fetch(messageId);
      if (!message) throw new Error("Message not found");

      // Verify we can edit it by trying to edit it (or just assume we can if it's ours)
      if (message.author.id !== interaction.client.user.id) {
        await interaction.editReply({
          components: [
            errorContainer("I cannot manage a message sent by another user."),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      const prior = await loadLeaderboardState();
      const season =
        interaction.options.getInteger("season") ??
        prior.season ??
        DEFAULT_LEADERBOARD_SEASON;

      await saveLeaderboardState({
        channelId: channel.id,
        messageId: message.id,
        season,
      });

      // Force an update immediately to ensure it looks right

      const data = await fetchLeaderboardData(season);

      if (data) {
        await message.edit({
          components: buildLeaderboardComponents(data, undefined, season),
          flags: MessageFlags.IsComponentsV2,
        });
      }

      await interaction.editReply({
        components: [
          primaryContainer(
            `## Leaderboard Set\nLeaderboard linked to message ${messageId} in <#${channel.id}> showing season ${season}.`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
    } catch (error) {
      await interaction.editReply({
        components: [
          errorContainer(
            `Could not find or edit message ${messageId} in <#${channel.id}>.`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
    }
  }

  private async handleRefresh(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const state = await loadLeaderboardState();
    if (!state.channelId || !state.messageId) {
      await interaction.editReply({
        components: [
          errorContainer("No leaderboard message has been configured yet."),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const season = state.season ?? DEFAULT_LEADERBOARD_SEASON;
    const data = await fetchLeaderboardData(season);
    if (!data) {
      await interaction.editReply({
        components: [errorContainer("Failed to fetch leaderboard data.")],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const emojiMap = await syncLeaderboardEmojis(interaction.client, data.topPlayers);

    try {
      const channel = (await interaction.guild!.channels.fetch(
        state.channelId,
      )) as TextChannel;
      if (!channel) throw new Error("Channel not found");
      const message = await channel.messages.fetch(state.messageId);
      if (!message) throw new Error("Message not found");

      await message.edit({
        components: buildLeaderboardComponents(data, emojiMap, season),
        flags: MessageFlags.IsComponentsV2,
      });

      await interaction.editReply({
        components: [primaryContainer("Leaderboard updated successfully.")],
        flags: MessageFlags.IsComponentsV2,
      });
    } catch (error) {
      await interaction.editReply({
        components: [
          errorContainer(
            "Failed to update the leaderboard message. It might have been deleted.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
    }
  }

  async build(
    _command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    const builder = new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)

      .addSubcommand((sub) =>
        sub
          .setName("create")
          .setDescription("Create a new leaderboard message in this channel")
          .addIntegerOption((opt) =>
            opt
              .setName("season")
              .setDescription("Season ID to show (default: last used)")
              .setMinValue(1),
          ),
      )

      .addSubcommand((sub) =>
        sub
          .setName("set")
          .setDescription("Set an existing message as the leaderboard")
          .addStringOption((opt) =>
            opt
              .setName("message_id")
              .setDescription("The ID of the message to edit")
              .setRequired(true),
          )
          .addChannelOption((opt) =>
            opt
              .setName("channel")
              .setDescription(
                "The channel the message is in (default: current)",
              ),
          )
          .addIntegerOption((opt) =>
            opt
              .setName("season")
              .setDescription("Season ID to show (default: last used)")
              .setMinValue(1),
          ),
      )
      .addSubcommand((sub) =>
        sub.setName("refresh").setDescription("Force refresh the leaderboard"),
      )
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
      .setDMPermission(false);

    return builder.toJSON();
  }
}
