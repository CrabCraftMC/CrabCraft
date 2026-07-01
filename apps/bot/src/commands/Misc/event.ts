import SlashCommand from "../../structures/SlashCommand.js";
import config from "../../utils/config.js";
import { errorContainer } from "../../utils/embeds.js";
import {
  type ChatInputCommandInteraction,
  type GuildMember,
  MessageFlags,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
  SlashCommandBuilder as Builder,
} from "discord.js";

export default class EventCommand extends SlashCommand {
  constructor() {
    super("event", "Event utilities", {
      guildOnly: true,
      cooldown: 30,
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

    const sub = interaction.options.getSubcommand(true);
    switch (sub) {
      case "ping":
        await this.handlePing(interaction);
        break;

      default:
        await interaction.reply({
          components: [errorContainer("**Error!** Unknown subcommand")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        break;
    }
  }

  private async handlePing(interaction: ChatInputCommandInteraction) {
    if (!config.GAME_MASTER_ROLE_ID || !config.EVENT_ROLE_ID) {
      await interaction.reply({
        components: [
          errorContainer(
            "**Error!** Event roles are not configured in `apps/bot/config.json`.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const member = await interaction.guild!.members
      .fetch(interaction.user.id)
      .catch(() => null) as GuildMember | null;
    if (!member?.roles.cache.has(config.GAME_MASTER_ROLE_ID)) {
      await interaction.reply({
        components: [errorContainer("**Missing permissions**")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const eventRole = await interaction.guild!.roles
      .fetch(config.EVENT_ROLE_ID)
      .catch(() => null);
    if (!eventRole) {
      await interaction.reply({
        components: [
          errorContainer(
            "**Error!** The configured event role was not found in this server.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    await interaction.reply({
      content: `<@&${config.EVENT_ROLE_ID}>`,
      allowedMentions: { roles: [config.EVENT_ROLE_ID] },
    });
  }

  async build(
    _command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    return new Builder()
      .setName(this.name)
      .setDescription(this.description)
      .addSubcommand((sub) =>
        sub
          .setName("ping")
          .setDescription("Ping the event role"),
      )
      .toJSON();
  }
}
