import SlashCommand from "../../structures/SlashCommand.js";
import { errorContainer, primaryContainer } from "../../utils/embeds.js";
import config from "../../utils/config.js";
import {
  fetchOnlinePlayers,
  generatePlayerListImage,
} from "../../utils/playerListView.js";
import {
  AttachmentBuilder,
  EmbedBuilder,
  MessageFlags,
  SlashCommandBuilder,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";

export default class PlayerListCommand extends SlashCommand {
  constructor() {
    super("playerlist", "Show the currently online Minecraft players", {
      cooldown: 10,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply();

    const players = await fetchOnlinePlayers(config.CRABCRAFT_API_URL);
    if (!players) {
      await interaction.editReply({
        components: [
          errorContainer("Could not reach the CrabCraft API. Try again in a bit."),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    if (players.players.length === 0) {
      await interaction.editReply({
        components: [primaryContainer("There are no players online.")],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const image = await generatePlayerListImage(players);
    const file = new AttachmentBuilder(image, { name: "Tablist.png" });
    const embed = new EmbedBuilder()
      .setImage("attachment://Tablist.png")
      .setColor(0x55ff55);

    await interaction.editReply({
      embeds: [embed],
      files: [file],
    });
  }

  async build(
    _command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    const builder = new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .setDMPermission(false);

    return builder.toJSON();
  }
}
