import {
  AttachmentBuilder,
  SlashCommandBuilder,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";
import { getBingoCardForDiscordUser } from "@crabcraft/db/queries/bingo";
import SlashCommand from "../../structures/SlashCommand.js";
import { generateBingoCardImage } from "../../utils/bingoView.js";

export default class BingoCommand extends SlashCommand {
  constructor() {
    super("bingo", "Show your progress on this week's bingo card", { cooldown: 10, guildOnly: true });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply();
    const result = await getBingoCardForDiscordUser(
      interaction.user.id,
      Math.floor(Date.now() / 1_000),
    );
    if (!result) {
      await interaction.editReply("There isn't an active bingo card right now.");
      return;
    }
    if (!result.identity) {
      await interaction.editReply("Your Discord account is not linked to a Minecraft account.");
      return;
    }
    const cardImage = await generateBingoCardImage(
      result.card,
      result.completedTaskIds,
      result.identity.username || interaction.user.username,
    );
    await interaction.editReply({
      files: [new AttachmentBuilder(cardImage, {
        name: `bingo-${result.card.number}-${result.identity.uuid}.png`,
      })],
    });
  }

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    return new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .setDMPermission(false)
      .toJSON();
  }
}
