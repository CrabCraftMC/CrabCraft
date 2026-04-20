import SlashCommand from "../../structures/SlashCommand.js";
import { primaryContainer } from "../../utils/embeds.js";
import {
  MessageFlags,
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  type ChatInputCommandInteraction,
} from "discord.js";

export default class WrappedCommand extends SlashCommand {
  constructor() {
    super("wrapped", "View your CrabCraft Wrapped stats from past seasons");
  }

  async execute(interaction: ChatInputCommandInteraction) {
    const button = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setLabel("View Your Wrapped")
        .setStyle(ButtonStyle.Link)
        .setURL("https://www.crabcraft.net/wrapped"),
    );

    await interaction.reply({
      components: [
        primaryContainer(
          "## 🎁 CrabCraft Wrapped\nCheck out your stats from past seasons! See your playtime, blocks placed, mobs defeated, and more.",
        ),
        button,
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }
}
