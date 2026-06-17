import SlashCommand from "../../structures/SlashCommand.js";
import * as appDb from "../../utils/appDb.js";

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
    const currentSeason = await appDb.getCurrentSeason().catch(() => null);
    const seasonName = currentSeason?.name ?? "the server";

    const button = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setCustomId("fast-apply")
        .setEmoji("🎄")
        .setLabel(seasonName.slice(0, 80))
        .setStyle(ButtonStyle.Primary),
    );

    (interaction.channel as TextChannel).send({
      content: `Want to join us for **${seasonName}?**\nClick the button below this message to gain access.`,
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
