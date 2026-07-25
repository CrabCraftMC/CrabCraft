import SlashCommand from "../../structures/SlashCommand.js";
import {
  errorContainer,
  primaryContainer,
  logAltAdded,
  logAltRemoved,
} from "../../utils/embeds.js";
import config from "../../utils/config.js";
import * as appDb from "../../utils/appDb.js";
import { resolveUsername } from "../../utils/mojang.js";
import {
  addPlayerAlt,
  getPlayerAlts,
  isAltUuidTaken,
  MAX_ALTS,
  removePlayerAlt,
} from "../../utils/appDb.js";
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ComponentType,
  MessageFlags,
  PermissionFlagsBits,
  SlashCommandBuilder,
  type ChatInputCommandInteraction,
  type GuildMember,
  type RESTPostAPIApplicationCommandsJSONBody,
  type TextChannel,
  type User,
} from "discord.js";

type ResolvedMinecraftUser = {
  uuid: string;
  name: string;
};

const CONFIRM_TIMEOUT_MS = 60_000;

export default class AltCommand extends SlashCommand {
  constructor() {
    super("alt", "Manage linked Minecraft alt accounts", {
      guildOnly: true,
      cooldown: 5,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.guild) {
      await this.replyError(
        interaction,
        "**Error!** This command can only be used in a server",
      );
      return;
    }

    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const executor = await interaction.guild.members.fetch(interaction.user.id);
    if (!executor.roles.cache.has(config.MOD_ROLE_ID)) {
      await this.replyError(interaction, "**Missing permissions**");
      return;
    }

    const sub = interaction.options.getSubcommand(true);
    switch (sub) {
      case "add":
        await this.handleAdd(interaction);
        break;

      case "list":
        await this.handleList(interaction);
        break;

      case "remove":
        await this.handleRemove(interaction);
        break;

      default:
        await this.replyError(interaction, "**Error!** Unknown subcommand");
        break;
    }
  }

  private async handleAdd(interaction: ChatInputCommandInteraction) {
    const targetUser = interaction.options.getUser("user", true);
    const minecraftUsername = interaction.options
      .getString("username", true)
      .trim();

    const member = await this.fetchTargetMember(interaction, targetUser);
    if (!member) return;

    const resolved = await this.resolveMinecraftUsername(
      interaction,
      minecraftUsername,
    );
    if (!resolved) return;

    const primaryUuid = await appDb.getPlayerPrimaryUuid(targetUser.id);
    if (!primaryUuid) {
      await this.replyError(
        interaction,
        "**Error!** That Discord user is not linked to a whitelisted Minecraft account.",
      );
      return;
    }

    if (primaryUuid === resolved.uuid) {
      await this.replyError(
        interaction,
        `**Error!** \`${resolved.name}\` is already <@${targetUser.id}>'s primary Minecraft account.`,
      );
      return;
    }

    const primaryOwner = await appDb.getPlayerByMinecraftUuid(resolved.uuid);
    if (primaryOwner) {
      await this.replyError(
        interaction,
        `**Error!** \`${resolved.name}\` is already linked as a primary Minecraft account.`,
      );
      return;
    }

    const alts = await getPlayerAlts(targetUser.id);
    if (alts.some((alt) => alt.minecraft_uuid === resolved.uuid)) {
      await this.replyError(
        interaction,
        `**Error!** \`${resolved.name}\` is already listed as an alt for <@${targetUser.id}>.`,
      );
      return;
    }

    if (await isAltUuidTaken(resolved.uuid)) {
      await this.replyError(
        interaction,
        `**Error!** \`${resolved.name}\` is already linked as an alt account.`,
      );
      return;
    }

    if (alts.length >= MAX_ALTS) {
      const confirmed = await this.confirmExtraAlt(interaction, alts.length);
      if (!confirmed) return;
    }

    try {
      await addPlayerAlt(targetUser.id, resolved.uuid, resolved.name);
    } catch {
      await this.respondError(
        interaction,
        "**Error!** Unable to add that alt account. It may already be linked.",
      );
      return;
    }

    await this.sendAltLog(
      interaction,
      logAltAdded(member.id, resolved.name, `${interaction.user}`),
    );

    await this.respondPrimary(
      interaction,
      `## Alt Added\nAdded \`${resolved.name}\` as an alt for <@${member.id}>.`,
    );
  }

  private async handleList(interaction: ChatInputCommandInteraction) {
    const targetUser = interaction.options.getUser("user", true);
    const alts = await getPlayerAlts(targetUser.id);

    if (alts.length === 0) {
      await this.replyPrimary(
        interaction,
        `## Alt Accounts\n<@${targetUser.id}> has no linked alt accounts.`,
      );
      return;
    }

    const lines = alts
      .sort((a, b) => a.created_at - b.created_at)
      .map(
        (alt, index) =>
          `${index + 1}. \`${alt.minecraft_username}\` (${alt.minecraft_uuid})`,
      )
      .join("\n");

    await this.replyPrimary(
      interaction,
      `## Alt Accounts\n<@${targetUser.id}> has ${alts.length} linked alt${alts.length === 1 ? "" : "s"}.\n\n${lines}`,
    );
  }

  private async handleRemove(interaction: ChatInputCommandInteraction) {
    const targetUser = interaction.options.getUser("user", true);
    const minecraftUsername = interaction.options
      .getString("username", true)
      .trim();

    const resolved = await this.resolveMinecraftUsername(
      interaction,
      minecraftUsername,
    );
    if (!resolved) return;

    const removed = await removePlayerAlt(targetUser.id, resolved.uuid);
    if (!removed) {
      await this.replyError(
        interaction,
        `**Error!** \`${resolved.name}\` is not listed as an alt for <@${targetUser.id}>.`,
      );
      return;
    }

    await this.sendAltLog(
      interaction,
      logAltRemoved(targetUser.id, resolved.name, `${interaction.user}`),
    );

    await this.replyPrimary(
      interaction,
      `## Alt Removed\nRemoved \`${resolved.name}\` from <@${targetUser.id}>'s linked alt accounts.`,
    );
  }

  /** Post an alt change to the log channel; failures are non-critical. */
  private async sendAltLog(
    interaction: ChatInputCommandInteraction,
    content: string,
  ) {
    const logChannel = await interaction
      .guild!.channels.fetch(config.LOG_CHANNEL_ID)
      .catch(() => null) as TextChannel | null;
    if (!logChannel) return;
    await logChannel
      .send({ content, allowedMentions: { parse: [] } })
      .catch(() => null);
  }

  private async fetchTargetMember(
    interaction: ChatInputCommandInteraction,
    targetUser: User,
  ): Promise<GuildMember | null> {
    const member = await interaction.guild!.members
      .fetch(targetUser.id)
      .catch(() => null);

    if (!member) {
      await this.replyError(
        interaction,
        "**Error!** The specified Discord user is not in this server.",
      );
      return null;
    }

    return member;
  }

  private async resolveMinecraftUsername(
    interaction: ChatInputCommandInteraction,
    minecraftUsername: string,
  ): Promise<ResolvedMinecraftUser | null> {
    const resolved = await resolveUsername(minecraftUsername);
    if (!resolved) {
      await this.replyError(
        interaction,
        `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.`,
      );
      return null;
    }

    return resolved;
  }

  private async confirmExtraAlt(
    interaction: ChatInputCommandInteraction,
    altCount: number,
  ): Promise<boolean> {
    const confirmId = `alt_add_confirm:${interaction.id}`;
    const cancelId = `alt_add_cancel:${interaction.id}`;

    const buttons = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setCustomId(confirmId)
        .setLabel("Add alt")
        .setStyle(ButtonStyle.Danger),
      new ButtonBuilder()
        .setCustomId(cancelId)
        .setLabel("Cancel")
        .setStyle(ButtonStyle.Secondary),
    );

    const components = [
      primaryContainer(
        `This user already has ${altCount} alts. Are you sure you'd like to add one more?`,
      ),
      buttons,
    ];

    if (interaction.replied || interaction.deferred) {
      await interaction.editReply({
        components,
        flags: MessageFlags.IsComponentsV2,
      });
    } else {
      await interaction.reply({
        components,
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
    }

    const reply = await interaction.fetchReply();
    try {
      const confirmation = await reply.awaitMessageComponent({
        componentType: ComponentType.Button,
        filter: (button) =>
          button.user.id === interaction.user.id &&
          (button.customId === confirmId || button.customId === cancelId),
        time: CONFIRM_TIMEOUT_MS,
      });

      if (confirmation.customId === cancelId) {
        await confirmation.update({
          components: [primaryContainer("Alt add cancelled.")],
          flags: MessageFlags.IsComponentsV2,
        });
        return false;
      }

      await confirmation.update({
        components: [primaryContainer("Adding alt...")],
        flags: MessageFlags.IsComponentsV2,
      });
      return true;
    } catch {
      await interaction
        .editReply({
          components: [primaryContainer("Alt add cancelled.")],
          flags: MessageFlags.IsComponentsV2,
        })
        .catch(() => null);
      return false;
    }
  }

  private async replyError(
    interaction: ChatInputCommandInteraction,
    message: string,
  ) {
    if (interaction.replied || interaction.deferred) {
      await interaction.editReply({
        components: [errorContainer(message)],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    await interaction.reply({
      components: [errorContainer(message)],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  private async replyPrimary(
    interaction: ChatInputCommandInteraction,
    message: string,
  ) {
    if (interaction.replied || interaction.deferred) {
      await interaction.editReply({
        components: [primaryContainer(message)],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    await interaction.reply({
      components: [primaryContainer(message)],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  private async respondError(
    interaction: ChatInputCommandInteraction,
    message: string,
  ) {
    if (interaction.replied || interaction.deferred) {
      await interaction.editReply({
        components: [errorContainer(message)],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    await this.replyError(interaction, message);
  }

  private async respondPrimary(
    interaction: ChatInputCommandInteraction,
    message: string,
  ) {
    if (interaction.replied || interaction.deferred) {
      await interaction.editReply({
        components: [primaryContainer(message)],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    await this.replyPrimary(interaction, message);
  }

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    const builder = new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .addSubcommand((sub) =>
        sub
          .setName("add")
          .setDescription("Add a Minecraft alt account for a Discord user")
          .addUserOption((opt) =>
            opt
              .setName("user")
              .setDescription("The Discord user this alt belongs to")
              .setRequired(true),
          )
          .addStringOption((opt) =>
            opt
              .setName("username")
              .setDescription("The alt's Minecraft Java username")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("list")
          .setDescription("List a Discord user's linked Minecraft alts")
          .addUserOption((opt) =>
            opt
              .setName("user")
              .setDescription("The Discord user to list alts for")
              .setRequired(true),
          ),
      )
      .addSubcommand((sub) =>
        sub
          .setName("remove")
          .setDescription("Remove a Minecraft alt account from a Discord user")
          .addUserOption((opt) =>
            opt
              .setName("user")
              .setDescription("The Discord user this alt belongs to")
              .setRequired(true),
          )
          .addStringOption((opt) =>
            opt
              .setName("username")
              .setDescription("The alt's Minecraft Java username")
              .setRequired(true),
          ),
      )
      .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
      .setDMPermission(false);

    return builder.toJSON();
  }
}
