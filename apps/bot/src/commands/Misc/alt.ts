import SlashCommand from "../../structures/SlashCommand.js";
import {
  errorContainer,
  primaryContainer,
  successContainer,
} from "../../utils/embeds.js";
import config from "../../utils/config.js";
import {
  addPlayerAlt,
  removePlayerAlt,
  getPlayerAlts,
  getAltCountForUser,
  isAltUuidTaken,
  MAX_ALTS,
} from "../../utils/altDb.js";
import { resolveUsername } from "../../utils/mojang.js";
import {
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
  SlashCommandBuilder as Builder,
  MessageFlags,
} from "discord.js";

export default class AltCommand extends SlashCommand {
  constructor() {
    super("alt", "Manage your alt Minecraft accounts", {
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

    // Only whitelisted members can manage alts
    const member = await interaction.guild.members.fetch(interaction.user.id);
    if (!member.roles.cache.has(config.MEMBER_ROLE_ID)) {
      await interaction.reply({
        components: [
          errorContainer(
            "**Missing permissions**\nYou must be a whitelisted member to manage alt accounts.",
          ),
        ],
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
    const username = interaction.options.getString("username", true).trim();

    await interaction.deferReply({
      flags: MessageFlags.Ephemeral,
    });

    // Check alt limit
    const count = await getAltCountForUser(interaction.user.id);
    if (count >= MAX_ALTS) {
      await interaction.editReply({
        components: [
          errorContainer(
            `**Alt limit reached!** You can only have ${MAX_ALTS} alt accounts.`,
          ),
        ],
      });
      return;
    }

    // Resolve Minecraft username via Mojang API
    const resolved = await resolveUsername(username);
    if (!resolved) {
      await interaction.editReply({
        components: [
          errorContainer(
            `**Sorry**, the username \`${username}\` is not a valid Minecraft Java account.`,
          ),
        ],
      });
      return;
    }

    // Check if this UUID is already registered as an alt
    const taken = await isAltUuidTaken(resolved.uuid);
    if (taken) {
      await interaction.editReply({
        components: [
          errorContainer(
            `**Already linked!** The account \`${resolved.name}\` is already registered as an alt.`,
          ),
        ],
      });
      return;
    }

    try {
      await addPlayerAlt(interaction.user.id, resolved.uuid, resolved.name);
    } catch {
      await interaction.editReply({
        components: [
          errorContainer(
            "**Error!** Failed to add alt account. It may already be linked.",
          ),
        ],
      });
      return;
    }

    await interaction.editReply({
      components: [
        successContainer(
          `### Alt Account Added\n**Username:** \`${resolved.name}\`\n**UUID:** \`${resolved.uuid}\`\n\nThis account can now join the server.`,
        ),
      ],
    });
  }

  private async handleRemove(interaction: ChatInputCommandInteraction) {
    const username = interaction.options.getString("username", true).trim();

    await interaction.deferReply({
      flags: MessageFlags.Ephemeral,
    });

    const resolved = await resolveUsername(username);
    if (!resolved) {
      await interaction.editReply({
        components: [
          errorContainer(
            `**Sorry**, the username \`${username}\` is not a valid Minecraft Java account.`,
          ),
        ],
      });
      return;
    }

    const removed = await removePlayerAlt(interaction.user.id, resolved.uuid);
    if (removed) {
      await interaction.editReply({
        components: [
          successContainer(
            `### Alt Account Removed\n**Username:** \`${resolved.name}\`\n\nThis account can no longer join the server.`,
          ),
        ],
      });
    } else {
      await interaction.editReply({
        components: [
          errorContainer(
            `**Not found!** \`${resolved.name}\` is not linked as one of your alt accounts.`,
          ),
        ],
      });
    }
  }

  private async handleList(interaction: ChatInputCommandInteraction) {
    const alts = await getPlayerAlts(interaction.user.id);

    if (alts.length === 0) {
      await interaction.reply({
        components: [
          primaryContainer(
            `### Your Alt Accounts\nYou have no alt accounts linked. Use \`/alt add\` to link one (max ${MAX_ALTS}).`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const lines = alts.map(
      (alt) =>
        `- \`${alt.minecraft_username}\` · <t:${alt.created_at}:R>`,
    );

    await interaction.reply({
      components: [
        primaryContainer(
          `### Your Alt Accounts (${alts.length}/${MAX_ALTS})\n${lines.join("\n")}`,
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
          .setName("add")
          .setDescription("Link an alt Minecraft account")
          .addStringOption((opt) =>
            opt
              .setName("username")
              .setDescription("The Minecraft Java username of your alt")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("remove")
          .setDescription("Unlink an alt Minecraft account")
          .addStringOption((opt) =>
            opt
              .setName("username")
              .setDescription("The Minecraft Java username to remove")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("list")
          .setDescription("Show all your linked alt accounts"),
      )
      .setDMPermission(false);

    return builder.toJSON();
  }
}
