import SlashCommand from "../../structures/SlashCommand.js";
import { errorContainer, primaryContainer, logAccept, logAdminWipe } from "../../utils/embeds.js";
import config from "../../utils/config.js";
import mysql from "../../utils/database.js";
import * as appDb from "../../utils/appDb.js";
import { resolveUsername, fetchPlayerName } from "../../utils/mojang.js";
import {
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type SlashCommandBuilder,
  SlashCommandBuilder as Builder,
  PermissionFlagsBits,
  MessageFlags,
  type GuildMember,
  type TextChannel,
} from "discord.js";


export default class AdminCommand extends SlashCommand {
  constructor() {
    super("admin", "Administrative utilities", {
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

    const executor = await interaction.guild!.members.fetch(
      interaction.user.id,
    );
    if (!executor.roles.cache.has(config.MOD_ROLE_ID)) {
      await interaction.reply({
        components: [errorContainer("**Missing permissions**")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const sub = interaction.options.getSubcommand(true);
    switch (sub) {
      case "whitelist":
        await this.handleWhitelist(interaction, executor);
        break;

      case "wipe-discord":
        await this.handleWipeDiscord(interaction, executor);
        break;

      case "wipe-minecraft":
        await this.handleWipeMinecraft(interaction, executor);
        break;

      default:
        await interaction.reply({
          components: [errorContainer("**Error!** Unknown subcommand")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        break;
    }
  }

  private async handleWhitelist(
    interaction: ChatInputCommandInteraction,
    executor: GuildMember,
  ) {
    const targetUser = interaction.options.getUser("user", true);
    const minecraftUsername = interaction.options
      .getString("username", true)
      .trim();

    const resolved = await resolveUsername(minecraftUsername);
    if (!resolved) {
      await interaction.reply({
        components: [
          errorContainer(
            `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const applicant = await interaction
      .guild!.members.fetch(targetUser.id)
      .catch(() => null);
    if (!applicant) {
      await interaction.reply({
        components: [
          errorContainer(
            "**Error!** The specified Discord user is not in this server.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const UUID = resolved.uuid;

    try {
      const existing = await mysql.query(
        "SELECT * FROM discordsrv_accounts WHERE uuid = ? OR discord = ?",
        [UUID, targetUser.id],
      );
      if (existing.length > 0) {
        await interaction.reply({
          components: [
            errorContainer(
              "**Error!** This Discord user or Minecraft account is already linked/whitelisted.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }
    } catch {
      // Fallthrough
    }

    try {
      await mysql.query(
        "INSERT INTO discordsrv_accounts (uuid, discord) VALUES (?, ?)",
        [UUID, targetUser.id],
      );
    } catch {
      await interaction.reply({
        components: [
          errorContainer(
            "**Error!** Unable to add this user to the whitelist. They may already be whitelisted.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const memberRole = interaction.guild!.roles.cache.get(
      config.MEMBER_ROLE_ID,
    );
    if (memberRole) {
      await applicant.roles.add(memberRole).catch(() => null);
    }

    await appDb.upsertUser({
      discordId: applicant.id,
      discordUsername: applicant.user.username,
      minecraftUsername: minecraftUsername,
      minecraftUuid: UUID,
    });

    const logChannel = await interaction.guild!.channels
      .fetch(config.LOG_CHANNEL_ID)
      .catch(() => null) as TextChannel | null;
    if (logChannel) {
      await logChannel.send({
        components: [logAccept(applicant.id, minecraftUsername, UUID, `${executor}`)],
        flags: MessageFlags.IsComponentsV2,
      });
    }

    await interaction.reply({
      components: [
        primaryContainer(
          `## User Whitelisted\nSuccessfully whitelisted <@${applicant.id}> as \`${minecraftUsername}\`.`,
        ),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  private async handleWipeDiscord(
    interaction: ChatInputCommandInteraction,
    executor: GuildMember,
  ) {
    const targetUser = interaction.options.getUser("user", true);

    let rows: any[] = [];
    try {
      rows = await mysql.query(
        "SELECT * FROM discordsrv_accounts WHERE discord = ?",
        [targetUser.id],
      );
    } catch {
      // ignore
    }

    if (rows.length === 0) {
      await interaction.reply({
        components: [errorContainer("No data found for that Discord user.")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const playerName = await fetchPlayerName(rows[0].uuid as string);

    try {
      await mysql.query(
        "DELETE FROM discordsrv_accounts WHERE discord = ?",
        [targetUser.id],
      );
    } catch {
      await interaction.reply({
        components: [
          errorContainer(
            "**Error!** Failed to wipe data for that Discord user.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const member = await interaction
      .guild!.members.fetch(targetUser.id)
      .catch(() => null);
    if (member) {
      const role = interaction.guild!.roles.cache.get(config.MEMBER_ROLE_ID);
      if (role) await member.roles.remove(role).catch(() => null);
    }

    const logChannel = await interaction.guild!.channels
      .fetch(config.LOG_CHANNEL_ID)
      .catch(() => null) as TextChannel | null;
    if (logChannel) {
      await logChannel.send({
        components: [logAdminWipe(`<@${targetUser.id}>`, playerName, `${executor}`)],
        flags: MessageFlags.IsComponentsV2,
      });
    }

    await interaction.reply({
      components: [
        primaryContainer(
          `## Wipe Complete\nData wiped for <@${targetUser.id}>. Removed linked Minecraft account \`${playerName}\` from the database.`,
        ),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  private async handleWipeMinecraft(
    interaction: ChatInputCommandInteraction,
    executor: GuildMember,
  ) {
    const minecraftUsername = interaction.options
      .getString("username", true)
      .trim();

    const resolved = await resolveUsername(minecraftUsername);
    if (!resolved) {
      await interaction.reply({
        components: [
          errorContainer(
            `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    const UUID = resolved.uuid;

    let linkedDiscordId: string | undefined = undefined;
    try {
      const rows = await mysql.query(
        "SELECT * FROM discordsrv_accounts WHERE uuid = ?",
        [UUID],
      );
      if (rows.length > 0) linkedDiscordId = rows[0].discord;
    } catch {
      // ignore
    }

    try {
      await mysql.query(
        "DELETE FROM discordsrv_accounts WHERE uuid = ?",
        [UUID],
      );
    } catch {
      await interaction.reply({
        components: [
          errorContainer(
            "**Error!** Failed to wipe data for that Minecraft account.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }

    if (linkedDiscordId) {
      const member = await interaction
        .guild!.members.fetch(linkedDiscordId)
        .catch(() => null);
      if (member) {
        const role = interaction.guild!.roles.cache.get(config.MEMBER_ROLE_ID);
        if (role) await member.roles.remove(role).catch(() => null);
      }
    }

    const logChannel = await interaction.guild!.channels
      .fetch(config.LOG_CHANNEL_ID)
      .catch(() => null) as TextChannel | null;
    if (logChannel) {
      await logChannel.send({
        components: [logAdminWipe(
          linkedDiscordId ? `<@${linkedDiscordId}>` : `\`${minecraftUsername}\``,
          minecraftUsername,
          `${executor}`,
          linkedDiscordId ? `Linked Discord <@${linkedDiscordId}> removed.` : undefined,
        )],
        flags: MessageFlags.IsComponentsV2,
      });
    }

    await interaction.reply({
      components: [
        primaryContainer(
          `## Wipe Complete\nData wiped for Minecraft user \`${minecraftUsername}\`${linkedDiscordId ? ` (Linked Discord: <@${linkedDiscordId}>)` : ""}.`,
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
          .setName("whitelist")
          .setDescription("Whitelist a Discord user with a Minecraft username")
          .addUserOption((opt) =>
            opt
              .setName("user")
              .setDescription("The Discord user to whitelist")
              .setRequired(true),
          )
          .addStringOption((opt) =>
            opt
              .setName("username")
              .setDescription("The user's Minecraft Java username")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("wipe-discord")
          .setDescription("Wipe data for a Discord user")
          .addUserOption((opt) =>
            opt
              .setName("user")
              .setDescription("The Discord user to wipe")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("wipe-minecraft")
          .setDescription("Wipe data for a Minecraft username")
          .addStringOption((opt) =>
            opt
              .setName("username")
              .setDescription("The Minecraft Java username to wipe")
              .setRequired(true),
          ),
      )
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
      .setDMPermission(false);

    return builder.toJSON();
  }
}
