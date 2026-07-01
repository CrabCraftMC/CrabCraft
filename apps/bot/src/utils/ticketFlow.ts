import {
  ButtonInteraction,
  ChannelType,
  type ChatInputCommandInteraction,
  type Client,
  MessageFlags,
  ModalSubmitInteraction,
  PermissionFlagsBits,
  type TextChannel,
} from "discord.js";

import config from "./config.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";
import type { Ticket, TicketCategory } from "./appDb.js";
import type { TicketInfractionInfo } from "./infractions.js";
import {
  appendEvidence,
  buildChannelName,
  buildInfractionEmbedMessage,
  buildIntakeModal,
  buildStaffButtons,
  buildTicketHeader,
  buildTicketLimitNotice,
  buildTicketTopic,
  getPlayerInfo,
  type CategoryMeta,
  type EvidenceFile,
  type PlayerInfo,
} from "./ticket.js";
import { errorContainer, primaryContainer } from "./embeds.js";

/**
 * Open tickets the user has in a category whose channels still exist. Any row
 * whose channel was deleted outside the bot (e.g. a mod deleting it directly in
 * Discord) is pruned here so it stops counting against the open-ticket limit.
 */
export async function getLiveOpenTicketsForCategory(
  client: Client,
  userId: string,
  category: TicketCategory,
): Promise<Ticket[]> {
  const open = await appDb.listOpenTicketsForUser(userId);
  const sameCategory = open.filter((t) => t.category === category);

  const live: Ticket[] = [];
  for (const ticket of sameCategory) {
    const channel = await client.channels
      .fetch(ticket.channel_id)
      .catch(() => null);
    if (channel) {
      live.push(ticket);
      continue;
    }
    // Orphaned row — the channel no longer exists. Clean it up.
    await appDb
      .deleteTicketRow(ticket.id)
      .then(() =>
        logger.info(
          `Ticket: pruned orphaned row #${ticket.id} (channel ${ticket.channel_id} missing)`,
        ),
      )
      .catch((e) =>
        logger.error(`Ticket: failed to prune orphaned row #${ticket.id}:`, e),
      );
  }
  return live;
}

export interface OpenTicketParams {
  /** The interaction to reply on. Must already be deferred (ephemeral + V2). */
  interaction:
    | ModalSubmitInteraction
    | ButtonInteraction
    | ChatInputCommandInteraction;
  meta: CategoryMeta;
  player: PlayerInfo;
  /** Text intake answers keyed by field id. */
  intake: Record<string, string>;
  /** Files uploaded in the modal (e.g. griefing evidence). */
  evidenceFiles?: EvidenceFile[];
  /** Appeal punishment history, if any (rendered as a separate embed). */
  ticketInfractionInfo?: TicketInfractionInfo | null;
  /**
   * Who the ticket is for (gets channel access + shown as "opened by").
   * Defaults to the interaction user; staff can open on someone else's behalf.
   */
  opener?: { id: string; username: string };
}

/**
 * Create the ticket channel, persist the ticket, and post its opening messages.
 * Shared by the modal path (appeals / griefing) and the no-modal path (general
 * questions). The interaction must already be deferred with an ephemeral
 * Components V2 reply so the editReply calls below render.
 */
export async function openTicket(params: OpenTicketParams): Promise<void> {
  const { interaction, meta, player, intake } = params;
  const evidenceFiles = params.evidenceFiles ?? [];
  const ticketInfractionInfo = params.ticketInfractionInfo ?? null;
  const opener = params.opener ?? {
    id: interaction.user.id,
    username: interaction.user.username,
  };
  const onBehalf = opener.id !== interaction.user.id;

  // Resolve the ticket category. Each ticket is its own text channel created
  // beneath this category.
  const parentCategory = await interaction.guild?.channels
    .fetch(config.TICKET_CATEGORY_ID)
    .catch(() => null);
  if (!parentCategory || parentCategory.type !== ChannelType.GuildCategory) {
    await interaction.editReply({
      components: [
        errorContainer(
          "**Configuration error.** The ticket category could not be found. Please contact an administrator.",
        ),
      ],
      flags: MessageFlags.IsComponentsV2,
    });
    return;
  }

  // Create the dedicated text channel. Permission overwrites hide it from
  // @everyone, grant the opener access, and grant the moderator role full access.
  let ticketChannel: TextChannel;
  try {
    ticketChannel = (await interaction.guild!.channels.create({
      name: buildChannelName(opener.username, meta),
      type: ChannelType.GuildText,
      parent: parentCategory.id,
      topic: `Ticket opened by ${opener.username} — category: ${meta.category}`,
      reason: `Ticket opened by ${opener.username} (${meta.category})`,
      permissionOverwrites: [
        {
          id: interaction.guild!.roles.everyone,
          deny: [PermissionFlagsBits.ViewChannel],
        },
        {
          id: opener.id,
          allow: [
            PermissionFlagsBits.ViewChannel,
            PermissionFlagsBits.SendMessages,
            PermissionFlagsBits.ReadMessageHistory,
          ],
        },
        {
          id: config.MOD_ROLE_ID,
          allow: [
            PermissionFlagsBits.ViewChannel,
            PermissionFlagsBits.SendMessages,
            PermissionFlagsBits.ReadMessageHistory,
            PermissionFlagsBits.ManageMessages,
          ],
        },
      ],
    })) as TextChannel;
  } catch (e) {
    logger.error("Ticket: failed to create channel:", e);
    await interaction.editReply({
      components: [
        errorContainer(
          "**Error!** Failed to create the ticket channel. Please try again, or contact a moderator directly.",
        ),
      ],
      flags: MessageFlags.IsComponentsV2,
    });
    return;
  }

  let ticket;
  try {
    ticket = await appDb.createTicket({
      channelId: ticketChannel.id,
      parentCategoryId: parentCategory.id,
      guildId: interaction.guildId!,
      openerDiscordId: opener.id,
      openerDiscordUsername: opener.username,
      openerMinecraftUuid: player.minecraftUuid,
      openerMinecraftUsername: player.minecraftUsername,
      category: meta.category,
      subject: null,
      intake,
    });
  } catch (e) {
    logger.error("Ticket: failed to persist ticket:", e);
    await ticketChannel.delete("Ticket persistence failed").catch(() => null);
    await interaction.editReply({
      components: [
        errorContainer(
          "**Error!** Failed to save your ticket. Please try again in a moment.",
        ),
      ],
      flags: MessageFlags.IsComponentsV2,
    });
    return;
  }

  // Set the structured topic now that we have the ticket id.
  await ticketChannel
    .setTopic(
      buildTicketTopic({
        ticketId: ticket.id,
        openerName: opener.username,
        meta,
        openedAtEpochSeconds: ticket.created_at,
      }),
    )
    .catch((e) => logger.error("Ticket: failed to set channel topic:", e));

  const headerPlayer = {
    minecraftUsername: player.minecraftUsername,
    minecraftUuid: player.minecraftUuid,
  };
  // "Opened by" is the actual creator (the invoker). For staff-opened tickets
  // the subject is shown as "For" and both are pinged.
  const mentions = {
    parse: [],
    users: onBehalf ? [opener.id, interaction.user.id] : [opener.id],
    roles: [],
  };

  // Evidence (griefing reports) rides inside the header container itself: media
  // in an inline gallery, other files as native File components (re-attached).
  const header = buildTicketHeader(
    meta,
    ticket.id,
    interaction.user.id,
    intake,
    headerPlayer,
    onBehalf ? opener.id : undefined,
  );
  const evidenceAttachments = appendEvidence(header, evidenceFiles);

  // The header carries the ticket info + evidence + Close button and is pinned.
  // It is never edited afterwards (the close/reopen lifecycle uses a separate
  // notice message) because its File components can't survive a re-send.
  let openingMessage: Awaited<ReturnType<typeof ticketChannel.send>> | null = null;
  try {
    openingMessage = await ticketChannel.send({
      components: [header, buildStaffButtons(ticket.id)],
      files: evidenceAttachments.length > 0 ? evidenceAttachments : undefined,
      allowedMentions: mentions,
      flags: MessageFlags.IsComponentsV2,
    });
  } catch (e) {
    // Most likely an evidence URL Discord wouldn't render — retry without it and
    // post the evidence as plain links so the ticket still opens.
    logger.error("Ticket: failed to send opening message with evidence:", e);
    openingMessage = await ticketChannel
      .send({
        components: [
          buildTicketHeader(
            meta,
            ticket.id,
            interaction.user.id,
            intake,
            headerPlayer,
            onBehalf ? opener.id : undefined,
          ),
          buildStaffButtons(ticket.id),
        ],
        allowedMentions: mentions,
        flags: MessageFlags.IsComponentsV2,
      })
      .catch((e2) => {
        logger.error("Ticket: failed to send fallback opening message:", e2);
        return null;
      });
    if (openingMessage && evidenceFiles.length > 0) {
      await ticketChannel
        .send({
          content: `**Evidence**\n${evidenceFiles.map((f) => f.url).join("\n")}`,
          allowedMentions: { parse: [] },
        })
        .catch(() => null);
    }
  }

  if (openingMessage) {
    await openingMessage
      .pin()
      .catch((e) => logger.error("Ticket: failed to pin opening message:", e));
  }

  // Appeals get a standard embed of the appellant's punishment history. It's a
  // separate message because a classic embed can't be mixed into the
  // Components V2 header above.
  const infractionMessage = buildInfractionEmbedMessage(
    ticket.id,
    ticketInfractionInfo,
  );
  if (infractionMessage) {
    await ticketChannel
      .send({
        embeds: infractionMessage.embeds,
        components: infractionMessage.components,
        allowedMentions: { parse: [] },
      })
      .catch((e) => logger.error("Ticket: failed to send infraction embed:", e));
  }

  await interaction.editReply({
    components: [
      primaryContainer(
        onBehalf
          ? `Opened a **${meta.emoji} ${meta.label}** ticket for <@${opener.id}>: <#${ticketChannel.id}>`
          : `Your **${meta.emoji} ${meta.label}** ticket has been created: <#${ticketChannel.id}>`,
      ),
    ],
    flags: MessageFlags.IsComponentsV2,
  });
}

/**
 * Start opening a ticket for the interaction user: enforce the per-category
 * limit, then either open directly (no-question categories) or show the intake
 * modal. Shared by the panel button and the /new command.
 */
export async function beginTicketOpen(
  interaction: ButtonInteraction | ChatInputCommandInteraction,
  meta: CategoryMeta,
): Promise<void> {
  try {
    const sameCategory = await getLiveOpenTicketsForCategory(
      interaction.client,
      interaction.user.id,
      meta.category,
    );
    if (sameCategory.length >= meta.maxOpen) {
      await interaction.reply({
        components: [
          errorContainer(
            buildTicketLimitNotice(
              meta,
              sameCategory.map((t) => t.channel_id),
            ),
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      return;
    }
  } catch (e) {
    logger.error("Ticket: rate-limit check failed:", e);
  }

  // Categories with no intake questions (e.g. General Question) open straight away.
  if (meta.fields.length === 0 && !meta.fileField) {
    await interaction.deferReply({
      flags: MessageFlags.Ephemeral | MessageFlags.IsComponentsV2,
    });
    const player = await getPlayerInfo(interaction.user.id, interaction.user.tag);
    await openTicket({ interaction, meta, player, intake: {} });
    return;
  }

  // Appeals prompt for a Minecraft username only when the user isn't linked.
  let includeMinecraftUsername = false;
  if (meta.category === "appeal") {
    try {
      const player = await getPlayerInfo(interaction.user.id, interaction.user.tag);
      includeMinecraftUsername = !player.minecraftUuid;
    } catch (e) {
      logger.error("Ticket: appeal link check failed:", e);
      includeMinecraftUsername = true;
    }
  }

  try {
    await interaction.showModal(buildIntakeModal(meta, { includeMinecraftUsername }));
  } catch (e) {
    logger.error("Ticket: failed to show intake modal:", e);
    await interaction
      .reply({
        components: [
          errorContainer(
            "**Error!** Couldn't open the ticket form. Please try again, or contact a moderator.",
          ),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      })
      .catch(() => null);
  }
}
