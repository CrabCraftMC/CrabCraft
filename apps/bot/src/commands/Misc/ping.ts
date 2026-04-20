import SlashCommand from "../../structures/SlashCommand.js";
import { primaryContainer } from "../../utils/embeds.js";
import { MessageFlags, type ChatInputCommandInteraction } from "discord.js";

export default class PingCommand extends SlashCommand {
  constructor() {
    super("ping", "Replies with Pong!", { cooldown: 5 });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply();

    const ping = interaction.client.ws.ping;

    await interaction.editReply({
      components: [
        primaryContainer(
          `Currently latency is: [\`${ping}ms\`](https://discord.com/channels/${interaction.guild?.id}/${interaction.channel?.id}/${interaction.id})`,
        ),
      ],
      flags: MessageFlags.IsComponentsV2,
    });
  }
}
