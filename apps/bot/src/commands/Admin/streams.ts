import SlashCommand from "../../structures/SlashCommand.js";
import {
  errorContainer,
  primaryContainer,
  successContainer,
} from "../../utils/embeds.js";
import config from "../../utils/config.js";
import {
  addStreamChannel,
  removeStreamChannel,
  getAllStreamChannels,
  type Platform,
} from "../../utils/streamDb.js";
import {
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
  SlashCommandBuilder as Builder,
  PermissionFlagsBits,
  MessageFlags,
} from "discord.js";

const VALID_PLATFORMS: Platform[] = ["youtube", "twitch", "tiktok"];

export default class StreamsCommand extends SlashCommand {
  constructor() {
    super("streams", "Manage streaming channel monitors", {
      guildOnly: true,
      cooldown: 5,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.guild) {
      await interaction.reply({
        components: [
          errorContainer("**Error!** This command can only be used in a server"),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

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
      case "add":
        await this.handleAdd(interaction);
        break;
      case "remove":
        await this.handleRemove(interaction);
        break;
      case "list":
        await this.handleList(interaction);
        break;
      default:
        await interaction.reply({
          components: [errorContainer("**Error!** Unknown subcommand")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        break;
    }
  }

  private async handleAdd(interaction: ChatInputCommandInteraction) {
    const platform = interaction.options.getString("platform", true) as Platform;
    const channelId = interaction.options.getString("channel_id", true).trim();
    const user = interaction.options.getUser("user", true);
    const displayName = interaction.options.getString("name")?.trim() ?? undefined;

    if (!VALID_PLATFORMS.includes(platform)) {
      await interaction.reply({
        components: [
          errorContainer(`**Error!** Invalid platform. Use: ${VALID_PLATFORMS.join(", ")}`),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    try {
      await addStreamChannel(platform, channelId, user.id, displayName);
      await interaction.reply({
        components: [
          successContainer(
            `### Stream Channel Added\n**Platform:** ${platform}\n**Channel:** \`${channelId}\`${displayName ? ` (${displayName})` : ""}\n**Discord User:** <@${user.id}>\n\nThey will receive the live role when this channel goes live.`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
    } catch (error) {
      await interaction.reply({
        components: [
          errorContainer("**Error!** Failed to add stream channel. It may already exist."),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
    }
  }

  private async handleRemove(interaction: ChatInputCommandInteraction) {
    const platform = interaction.options.getString("platform", true) as Platform;
    const channelId = interaction.options.getString("channel_id", true).trim();

    const removed = await removeStreamChannel(platform, channelId);
    if (removed) {
      await interaction.reply({
        components: [
          successContainer(
            `### Stream Channel Removed\n**Platform:** ${platform}\n**Channel:** \`${channelId}\``,
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
    } else {
      await interaction.reply({
        components: [
          errorContainer("**Error!** No stream channel found with that platform and ID."),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
    }
  }

  private async handleList(interaction: ChatInputCommandInteraction) {
    const channels = await getAllStreamChannels();

    if (channels.length === 0) {
      await interaction.reply({
        components: [
          primaryContainer("### Monitored Streams\nNo stream channels are being monitored."),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const lines = channels.map((ch) => {
      const name = ch.display_name ? ` (${ch.display_name})` : "";
      return `- **${ch.platform}** · \`${ch.channel_id}\`${name} → <@${ch.discord_user_id}>`;
    });

    await interaction.reply({
      components: [
        primaryContainer(`### Monitored Streams\n${lines.join("\n")}`),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  async build(
    _command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    const builder = new Builder()
      .setName(this.name)
      .setDescription(this.description)
      .addSubcommand((sub) =>
        sub
          .setName("add")
          .setDescription("Add a streaming channel to monitor")
          .addStringOption((opt) =>
            opt
              .setName("platform")
              .setDescription("The streaming platform")
              .setRequired(true)
              .addChoices(
                { name: "YouTube", value: "youtube" },
                { name: "Twitch", value: "twitch" },
                { name: "TikTok", value: "tiktok" },
              ),
          )
          .addStringOption((opt) =>
            opt
              .setName("channel_id")
              .setDescription("Channel ID (YouTube) or username (Twitch/TikTok)")
              .setRequired(true),
          )
          .addUserOption((opt) =>
            opt
              .setName("user")
              .setDescription("Discord user to receive the live role")
              .setRequired(true),
          )
          .addStringOption((opt) =>
            opt
              .setName("name")
              .setDescription("Optional display name for the channel")
              .setRequired(false),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("remove")
          .setDescription("Remove a streaming channel from monitoring")
          .addStringOption((opt) =>
            opt
              .setName("platform")
              .setDescription("The streaming platform")
              .setRequired(true)
              .addChoices(
                { name: "YouTube", value: "youtube" },
                { name: "Twitch", value: "twitch" },
                { name: "TikTok", value: "tiktok" },
              ),
          )
          .addStringOption((opt) =>
            opt
              .setName("channel_id")
              .setDescription("Channel ID (YouTube) or username (Twitch/TikTok)")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("list")
          .setDescription("List all monitored streaming channels"),
      )
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
      .setDMPermission(false);

    return builder.toJSON();
  }
}
