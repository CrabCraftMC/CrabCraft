import SlashCommand from "../../structures/SlashCommand.js";

import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  PermissionFlagsBits,
  TextChannel,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
} from "discord.js";

export default class AccessCCCommand extends SlashCommand {
  constructor() {
    super("accesscc", "Sends the direct access embed");
  }

  async execute(interaction: ChatInputCommandInteraction) {
    const button = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setCustomId("fast-apply")
        .setEmoji("🎄")
        .setLabel("Season 6")
        .setStyle(ButtonStyle.Primary),
    );

    (interaction.channel as TextChannel).send({
      content:
        "Want to join us for **Season 6?**\nClick the button below this message to gain access.",
      components: [button],
    });

    await interaction.reply({
      content: "The access embed has been sent!",
      ephemeral: true,
    });
  }

  async build(
    command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    return command
      .setDefaultMemberPermissions(PermissionFlagsBits.AddReactions)
      .toJSON();
  }
}
