import {
  ButtonInteraction,
  ChannelType,
  MessageFlags,
  ModalSubmitInteraction,
  PermissionFlagsBits,
  type TextChannel,
} from "discord.js";

import config from "./config.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";
import type { TicketInfractionInfo } from "./infractions.js";
import {
  buildChannelName,
  buildInfractionEmbedMessage,
  buildStaffButtons,
  buildTicketHeader,
  buildTicketTopic,
  type CategoryMeta,
  type PlayerInfo,
} from "./ticket.js";
import { errorContainer, primaryContainer } from "./embeds.js";

export interface OpenTicketParams {
  /** The interaction to reply on. Must already be deferred (ephemeral + V2). */
  interaction: ModalSubmitInteraction | ButtonInteraction;
  meta: CategoryMeta;
  player: PlayerInfo;
  /** Text intake answers keyed by field id. */
  intake: Record<string, string>;
  /** CDN URLs of any files uploaded in the modal (e.g. evidence). */
  evidenceFileUrls?: string[];
  /** Appeal punishment history, if any (rendered as a separate embed). */
  ticketInfractionInfo?: TicketInfractionInfo | null;
}

/**
 * Create the ticket channel, persist the ticket, and post its opening messages.
 * Shared by the modal path (appeals / griefing) and the no-modal path (general
 * questions). The interaction must already be deferred with an ephemeral
 * Components V2 reply so the editReply calls below render.
 */
export async function openTicket(params: OpenTicketParams): Promise<void> {
  const { interaction, meta, player, intake } = params;
  const evidenceFileUrls = params.evidenceFileUrls ?? [];
  const ticketInfractionInfo = params.ticketInfractionInfo ?? null;

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
    });
    return;
  }

  // Create the dedicated text channel. Permission overwrites hide it from
  // @everyone, grant the opener access, and grant the moderator role full access.
  let ticketChannel: TextChannel;
  try {
    ticketChannel = (await interaction.guild!.channels.create({
      name: buildChannelName(interaction.user.username, meta),
      type: ChannelType.GuildText,
      parent: parentCategory.id,
      topic: `Ticket opened by ${interaction.user.tag} — category: ${meta.category}`,
      reason: `Ticket opened by ${interaction.user.tag} (${meta.category})`,
      permissionOverwrites: [
        {
          id: interaction.guild!.roles.everyone,
          deny: [PermissionFlagsBits.ViewChannel],
        },
        {
          id: interaction.user.id,
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
    });
    return;
  }

  let ticket;
  try {
    ticket = await appDb.createTicket({
      channelId: ticketChannel.id,
      parentCategoryId: parentCategory.id,
      guildId: interaction.guildId!,
      openerDiscordId: interaction.user.id,
      openerDiscordUsername: interaction.user.username,
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
    });
    return;
  }

  // Set the structured topic now that we have the ticket id.
  await ticketChannel
    .setTopic(
      buildTicketTopic({
        ticketId: ticket.id,
        openerName: interaction.user.username,
        meta,
        openedAtEpochSeconds: ticket.created_at,
      }),
    )
    .catch((e) => logger.error("Ticket: failed to set channel topic:", e));

  try {
    const openingMessage = await ticketChannel.send({
      components: [
        buildTicketHeader(meta, ticket.id, interaction.user.id, intake),
        buildStaffButtons(ticket.id),
      ],
      allowedMentions: { parse: [], users: [interaction.user.id], roles: [] },
      flags: MessageFlags.IsComponentsV2,
    });

    // Pin the main ticket message so it's easy to find in the channel.
    await openingMessage
      .pin()
      .catch((e) => logger.error("Ticket: failed to pin opening message:", e));

    // Re-upload any evidence files into the channel so they persist (the modal
    // upload URLs are short-lived). Fall back to posting the links on failure.
    if (evidenceFileUrls.length > 0) {
      await ticketChannel
        .send({ content: "**Evidence**", files: evidenceFileUrls })
        .catch(async (e) => {
          logger.error("Ticket: failed to attach evidence files:", e);
          await ticketChannel
            .send({
              content: `**Evidence**\n${evidenceFileUrls.join("\n")}`,
              allowedMentions: { parse: [] },
            })
            .catch(() => null);
        });
    }

    // Appeals get a standard embed of the appellant's punishment history. It's a
    // separate message because a classic embed can't be mixed into the
    // Components V2 header above.
    const infractionMessage = buildInfractionEmbedMessage(
      ticket.id,
      ticketInfractionInfo,
    );
    if (infractionMessage) {
      await ticketChannel.send({
        embeds: infractionMessage.embeds,
        components: infractionMessage.components,
        allowedMentions: { parse: [] },
      });
    }
  } catch (e) {
    logger.error("Ticket: failed to send opening message:", e);
    await ticketChannel
      .send({
        content: `Ticket #${String(ticket.id).padStart(4, "0")} was opened by <@${interaction.user.id}> for ${meta.label}, but the rich ticket summary could not be posted automatically.`,
        allowedMentions: { parse: [], users: [interaction.user.id], roles: [] },
      })
      .catch((fallbackError) => {
        logger.error("Ticket: failed to send fallback opening message:", fallbackError);
      });
  }

  await interaction.editReply({
    components: [
      primaryContainer(
        `### Ticket Opened\nYour **${meta.label}** ticket is ready in <#${ticketChannel.id}>.`,
      ),
    ],
  });
}
