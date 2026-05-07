import SlashCommand from "../../structures/SlashCommand.js";
import {
  errorContainer,
  primaryContainer,
  successContainer,
} from "../../utils/embeds.js";
import config from "../../utils/config.js";
import {
  getCountingState,
  setCountingState,
} from "../../utils/appDb.js";
import {
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
  SlashCommandBuilder as Builder,
  PermissionFlagsBits,
  MessageFlags,
} from "discord.js";

export default class CountingCommand extends SlashCommand {
  constructor() {
    super("counting", "Manage the counting channel state", {
      guildOnly: true,
      cooldown: 5,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.guild) {
      await interaction.reply({
        components: [errorContainer("**Error!** This command can only be used in a server")],
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

    if (!config.COUNTING_CHANNEL_ID) {
      await interaction.reply({
        components: [errorContainer("**Error!** No counting channel configured (set `channels.counting` in `apps/bot/config.json`).")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const sub = interaction.options.getSubcommand(true);
    switch (sub) {
      case "set":
        await this.handleSet(interaction);
        break;
      case "view":
        await this.handleView(interaction);
        break;
      default:
        await interaction.reply({
          components: [errorContainer("**Error!** Unknown subcommand")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        break;
    }
  }

  private async handleSet(interaction: ChatInputCommandInteraction) {
    const value = interaction.options.getInteger("number", true);
    if (value < 0) {
      await interaction.reply({
        components: [errorContainer("**Error!** Count must be 0 or greater.")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    await setCountingState(config.COUNTING_CHANNEL_ID, value, null);
    await interaction.reply({
      components: [
        successContainer(
          `### Counting Updated\n**Current count:** \`${value}\`\nNext valid number is \`${value + 1}\`. Anyone may post it.`,
        ),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  private async handleView(interaction: ChatInputCommandInteraction) {
    const state = await getCountingState(config.COUNTING_CHANNEL_ID);
    if (!state) {
      await interaction.reply({
        components: [primaryContainer("### Counting\nNo state recorded yet. Use `/counting set` to seed it.")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const lastLine = state.last_user_id ? `**Last counter:** <@${state.last_user_id}>\n` : "";
    await interaction.reply({
      components: [
        primaryContainer(
          `### Counting\n**Current count:** \`${state.current_count}\`\n**Next valid:** \`${state.current_count + 1}\`\n${lastLine}-# Channel: <#${config.COUNTING_CHANNEL_ID}>`,
        ),
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
          .setName("set")
          .setDescription("Seed or override the current count")
          .addIntegerOption((opt) =>
            opt
              .setName("number")
              .setDescription("Current count (the last valid number posted)")
              .setMinValue(0)
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub.setName("view").setDescription("View the current counting state"),
      )
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
      .setDMPermission(false);

    return builder.toJSON();
  }
}
