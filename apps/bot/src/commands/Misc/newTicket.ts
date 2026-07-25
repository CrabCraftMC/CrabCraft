import SlashCommand from "../../structures/SlashCommand.js";
import {
  MessageFlags,
  SlashCommandBuilder,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";

import config from "../../utils/config.js";
import logger from "../../utils/logger.js";
import { errorContainer } from "../../utils/embeds.js";
import {
  TICKET_CATEGORIES,
  getCategoryMeta,
  getPlayerInfo,
} from "../../utils/ticket.js";
import {
  beginTicketOpen,
  getLiveOpenTicketsForCategory,
  openTicket,
} from "../../utils/ticketFlow.js";
import { resolveTarget } from "../../utils/ticketMembers.js";

export default class NewTicketCommand extends SlashCommand {
  constructor() {
    super("new", "Open a support ticket", { guildOnly: true, cooldown: 10 });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.guild) return;

    const meta = getCategoryMeta(interaction.options.getString("category", true));
    if (!meta) {
      await this.replyError(interaction, "Unknown ticket category.");
      return;
    }

    const userOpt = interaction.options.getUser("user", false);
    const usernameOpt = interaction.options.getString("username", false)?.trim();
    const onBehalf = Boolean(userOpt || usernameOpt);

    // No target → open a ticket for yourself, exactly like the panel button.
    if (!onBehalf) {
      await beginTicketOpen(interaction, meta);
      return;
    }

    // Opening for someone else is staff-only.
    const executor = await interaction.guild.members
      .fetch(interaction.user.id)
      .catch(() => null);
    if (!executor?.roles.cache.has(config.MOD_ROLE_ID)) {
      await this.replyError(
        interaction,
        "**Missing permissions:** only staff can open tickets for other users.",
      );
      return;
    }

    // Griefing/appeal tickets rely on the reporter's own form answers, which we
    // can't collect on their behalf. General and Council tickets have no form.
    if (meta.category !== "general" && meta.category !== "council") {
      await this.replyError(
        interaction,
        `**Error!** You can only open **${TICKET_CATEGORIES.general.label}** or **${TICKET_CATEGORIES.council.label}** tickets for another user. Griefing and appeal tickets need the person's own form responses.`,
      );
      return;
    }

    const target = await resolveTarget(interaction);
    if ("error" in target) {
      await this.replyError(interaction, `**Error!** ${target.error}`);
      return;
    }

    const member = await interaction.guild.members
      .fetch(target.id)
      .catch(() => null);
    if (!member) {
      await this.replyError(
        interaction,
        `**Error!** ${target.label} is not in this server.`,
      );
      return;
    }

    // Respect the target's per-category open-ticket limit.
    try {
      const open = await getLiveOpenTicketsForCategory(
        interaction.client,
        target.id,
        meta.category,
      );
      if (open.length >= meta.maxOpen) {
        await this.replyError(
          interaction,
          `**Error!** ${target.label} already has ${open.length} open ${meta.label} ticket${open.length === 1 ? "" : "s"}.`,
        );
        return;
      }
    } catch (e) {
      logger.error("Ticket: /new rate-limit check failed:", e);
    }

    await interaction.deferReply({
      flags: MessageFlags.Ephemeral | MessageFlags.IsComponentsV2,
    });

    const player = await getPlayerInfo(target.id, member.user.tag);

    await openTicket({
      interaction,
      meta,
      player,
      intake: {},
      opener: { id: target.id, username: member.user.username },
    });
  }

  private async replyError(
    interaction: ChatInputCommandInteraction,
    message: string,
  ) {
    await interaction.reply({
      components: [errorContainer(message)],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
  }

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    return new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .addStringOption((opt) =>
        opt
          .setName("category")
          .setDescription("Which kind of ticket to open")
          .setRequired(true)
          .addChoices(
            ...Object.values(TICKET_CATEGORIES).map((c) => ({
              name: c.label,
              value: c.category,
            })),
          ),
      )
      .addUserOption((opt) =>
        opt
          .setName("user")
          .setDescription("Staff only: open a ticket for this Discord user")
          .setRequired(false),
      )
      .addStringOption((opt) =>
        opt
          .setName("username")
          .setDescription("Staff only: open a ticket for this Minecraft username")
          .setRequired(false),
      )
      .setDMPermission(false)
      .toJSON();
  }
}
