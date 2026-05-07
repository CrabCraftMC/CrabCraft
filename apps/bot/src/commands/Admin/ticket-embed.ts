import SlashCommand from "../../structures/SlashCommand.js";
import { errorContainer, primaryContainer } from "../../utils/embeds.js";
import { buildTriggerButtons, buildTriggerEmbed } from "../../utils/ticket.js";
import {
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
  type TextChannel,
  ChannelType,
  MessageFlags,
  PermissionFlagsBits,
} from "discord.js";

export default class TicketEmbedCommand extends SlashCommand {
  constructor() {
    super("ticket-embed", "Post the ticket creation embed in this channel");
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.guild) {
      await interaction.reply({
        components: [errorContainer("**Error!** This command can only be used in a server.")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const channel = interaction.channel;
    if (!channel || channel.type !== ChannelType.GuildText) {
      await interaction.reply({
        components: [errorContainer("**Error!** This command must be run in a text channel.")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    try {
      await (channel as TextChannel).send({
        components: [buildTriggerEmbed(), buildTriggerButtons()],
        flags: MessageFlags.IsComponentsV2,
      });
    } catch (e) {
      await interaction.reply({
        components: [errorContainer(`**Error!** Failed to post the ticket embed: ${(e as Error).message}`)],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    await interaction.reply({
      components: [primaryContainer("Ticket embed posted.")],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  async build(
    command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    return command
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
      .setDMPermission(false)
      .toJSON();
  }
}
