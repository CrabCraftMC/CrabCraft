import Event from "../structures/Event.js";
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonInteraction,
  ComponentType,
  GuildMember,
  MessageFlags,
  ModalBuilder,
  OverwriteType,
  TextChannel,
  TextInputBuilder,
  TextInputStyle,
} from "discord.js";
import { errorContainer, successContainer, successContainerWithThumbnail, primaryContainer, coloredContainer, logAccept } from "../utils/embeds.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { createApplicationChannelFor } from "../utils/applicationChannel.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import { resolveUsername } from "../utils/mojang.js";
import { fetchPlayerInfractions } from "../utils/infractions.js";
import { CHANNEL_DELETE_DELAY_MS, TICKET_DELETE_DELAY_MS } from "../utils/constants.js";
import { saveTranscriptToLog } from "../utils/transcript.js";
import { applicationAcceptedMessage } from "../utils/applicationMessages.js";
import {
  buildChannelName,
  buildClosedNotice,
  buildClosedTicketButtons,
  buildDisabledClosedTicketButtons,
  buildInfractionEmbedMessage,
  buildReopenedNotice,
  getCategoryMeta,
  TICKET_INFRACTION_BUTTON_PREFIX,
} from "../utils/ticket.js";
import { beginTicketOpen } from "../utils/ticketFlow.js";
import { isUnknownChannelError } from "../utils/discordErrors.js";
import { withTicketLifecycleLock } from "../utils/ticketLifecycle.js";
import { buildDenyModal } from "../utils/denyReasons.js";
import {
  SEASON_PLAY_BUTTON_ID,
  buildOpenTicketButton,
} from "../utils/seasonAccess.js";
import { AnalyticsEvent } from "@crabcraft/shared/analytics";
import { captureMinecraftEvent } from "../utils/analytics.js";

function intakeString(intake: unknown, key: string): string | null {
  if (typeof intake !== "object" || intake === null) return null;
  const value = (intake as Record<string, unknown>)[key];
  return typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : null;
}

function interactionMemberHasRole(
  member: ButtonInteraction["member"],
  roleId: string,
): boolean {
  if (!member) return false;
  return Array.isArray(member.roles)
    ? member.roles.includes(roleId)
    : member.roles.cache.has(roleId);
}

async function normalizeTicketChannelName(
  channel: TextChannel,
  ticket: appDb.Ticket,
): Promise<void> {
  const meta = getCategoryMeta(ticket.category);
  if (!meta) return;
  const name = buildChannelName(
    ticket.opener_discord_username,
    meta,
    ticket.id,
  );
  if (channel.name === name) return;
  await channel
    .setName(name)
    .catch((e) => logger.error("Ticket: failed to add id to channel name:", e));
}

function matchesTicketChannelId(
  channel: TextChannel,
  ticketId: number,
): boolean {
  const paddedId = String(ticketId).padStart(4, "0");
  return (
    channel.name.endsWith(`-${paddedId}`) ||
    channel.topic?.split("\n").includes(`- **Ticket ID**: #${paddedId}`) ===
      true
  );
}

async function unlockTicketChannel(
  channel: TextChannel,
  openerId: string,
): Promise<boolean> {
  let unlocked = true;
  await channel.permissionOverwrites
    .edit(
      openerId,
      {
        ViewChannel: true,
        SendMessages: true,
        ReadMessageHistory: true,
      },
      {
        type: OverwriteType.Member,
        reason: "Ticket reopened: opener access restored",
      },
    )
    .catch((e) => {
      unlocked = false;
      logger.error("Ticket: failed to restore opener access:", e);
    });

  const everyoneId = channel.guild.roles.everyone.id;
  for (const overwrite of channel.permissionOverwrites.cache.values()) {
    if (overwrite.id === everyoneId || overwrite.id === openerId) continue;
    await channel.permissionOverwrites
      .edit(
        overwrite.id,
        { SendMessages: true },
        {
          reason: "Ticket reopened: channel unlocked",
          type: overwrite.type,
        },
      )
      .catch((e) => {
        unlocked = false;
        logger.error("Ticket: failed to unlock channel on reopen:", e);
      });
  }
  return unlocked;
}

async function lockTicketChannel(channel: TextChannel): Promise<boolean> {
  let locked = true;
  const everyoneId = channel.guild.roles.everyone.id;
  for (const overwrite of channel.permissionOverwrites.cache.values()) {
    if (overwrite.id === everyoneId) continue;
    await channel.permissionOverwrites
      .edit(
        overwrite.id,
        { SendMessages: false },
        {
          reason: "Ticket closed: channel locked",
          type: overwrite.type,
        },
      )
      .catch((e) => {
        locked = false;
        logger.error("Ticket: failed to lock channel on close:", e);
      });
  }
  return locked;
}

async function hasActiveClosedTicketNotice(
  channel: TextChannel,
  ticketId: number,
): Promise<boolean> {
  const reopenId = `ticket_reopen:${ticketId}`;
  const deleteId = `ticket_delete:${ticketId}`;
  const [recent, pinned] = await Promise.all([
    channel.messages.fetch({ limit: 100 }),
    channel.messages.fetchPins(),
  ]);
  const messages = [
    ...recent.values(),
    ...pinned.items.map((item) => item.message),
  ];
  return messages.some((message) =>
    message.components.some((row) => {
      if (row.type !== ComponentType.ActionRow) return false;
      const enabledIds = row.components.flatMap((component) =>
        component.type === ComponentType.Button && !component.disabled
          ? [component.customId]
          : [],
      );
      return enabledIds.includes(reopenId) && enabledIds.includes(deleteId);
    }),
  );
}

async function postClosedTicketNotice(
  channel: TextChannel,
  ticketId: number,
  closedByDiscordId: string,
  deleteAtSeconds: number,
): Promise<boolean> {
  return Boolean(
    await channel
      .send({
        components: [
          buildClosedNotice(`<@${closedByDiscordId}>`, deleteAtSeconds),
          buildClosedTicketButtons(ticketId),
        ],
        allowedMentions: { parse: [] },
        flags: MessageFlags.IsComponentsV2,
      })
      .catch((e) => {
        logger.error("Ticket: failed to send closed notice:", e);
        return null;
      }),
  );
}

type ClosedTicketRepairResult = "closed" | "open" | "failed";

async function performClosedTicketRepair(
  channel: TextChannel,
  ticketId: number,
  fallbackCloserId: string,
): Promise<ClosedTicketRepairResult> {
  let ticket: appDb.Ticket | null;
  try {
    ticket = await appDb.getTicketById(ticketId);
  } catch (e) {
    logger.error("Ticket: failed to recheck closed state:", e);
    return "failed";
  }
  if (!ticket) return "failed";
  if (ticket.status === "open") {
    return (await unlockTicketChannel(channel, ticket.opener_discord_id))
      ? "open"
      : "failed";
  }

  try {
    if (await hasActiveClosedTicketNotice(channel, ticket.id)) {
      return (await lockTicketChannel(channel)) ? "closed" : "failed";
    }
  } catch (e) {
    logger.error("Ticket: failed to verify closed controls:", e);
    return "failed";
  }

  if (ticket.delete_after == null) {
    logger.error("Ticket: closed row is missing its deletion deadline");
    return "failed";
  }

  const noticePosted = await postClosedTicketNotice(
    channel,
    ticket.id,
    ticket.closed_by_discord_id ?? fallbackCloserId,
    ticket.delete_after,
  );
  if (!noticePosted) return "failed";

  return (await lockTicketChannel(channel)) ? "closed" : "failed";
}

async function retireClosedTicketNotice(
  interaction: ButtonInteraction,
  ticketId: number,
): Promise<boolean> {
  try {
    const otherComponents = interaction.message.components.filter(
      (row) => row.type !== ComponentType.ActionRow,
    );
    await interaction.message.edit({
      components: [
        ...otherComponents,
        buildDisabledClosedTicketButtons(ticketId),
      ],
      flags: MessageFlags.IsComponentsV2,
    });
    return true;
  } catch (e) {
    logger.error("Ticket: failed to disable closed-notice buttons:", e);
  }

  try {
    await interaction.message.delete();
    return true;
  } catch (e) {
    logger.error("Ticket: failed to remove stale closed notice:", e);
    return false;
  }
}

async function followUpTicketError(
  interaction: ButtonInteraction,
  message: string,
): Promise<void> {
  await interaction.followUp({
    components: [errorContainer(message)],
    flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
  });
}

async function handleTicketClose(
  interaction: ButtonInteraction,
  ticketId: number,
): Promise<void> {
  let ticket: appDb.Ticket | null;
  try {
    ticket = await appDb.getTicketById(ticketId);
  } catch (e) {
    logger.error("Ticket: close lookup failed:", e);
    await followUpTicketError(interaction, "Failed to close. Please try again.");
    return;
  }
  if (!ticket) {
    await followUpTicketError(interaction, "Ticket not found.");
    return;
  }
  if (ticket.channel_id !== interaction.channelId) {
    await followUpTicketError(
      interaction,
      "This button is not for this channel.",
    );
    return;
  }
  const isStaff = interactionMemberHasRole(interaction.member, config.MOD_ROLE_ID)
    || interactionMemberHasRole(interaction.member, config.COUNCIL_ROLE_ID);
  if (interaction.user.id !== ticket.opener_discord_id && !isStaff) {
    await followUpTicketError(
      interaction,
      "Only the ticket opener or staff can close this ticket.",
    );
    return;
  }
  const ticketChannel = interaction.channel as TextChannel | null;
  if (!ticketChannel) {
    await followUpTicketError(interaction, "Ticket channel not found.");
    return;
  }

  await normalizeTicketChannelName(ticketChannel, ticket);

  // The header's Close button intentionally stays attached and active. If a
  // previous close stopped after updating the DB, finish its Discord state.
  if (ticket.status === "closed") {
    const repaired = await performClosedTicketRepair(
      ticketChannel,
      ticket.id,
      interaction.user.id,
    );
    await interaction.followUp({
      components: [
        repaired === "closed"
          ? primaryContainer("Ticket closed.")
          : repaired === "open"
            ? primaryContainer(
                "This ticket was reopened while Close was running.",
              )
            : errorContainer(
                "This ticket is marked closed, but its Discord state could not be verified. Please try again.",
              ),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
    return;
  }

  const deleteAtSeconds = Math.floor(
    (Date.now() + TICKET_DELETE_DELAY_MS) / 1000,
  );

  // Anyone with access to this (private) ticket channel may close it. The
  // conditional update makes simultaneous clicks safe across bot processes.
  try {
    const closed = await appDb.closeTicket(
      ticket.id,
      interaction.user.id,
      deleteAtSeconds,
    );
    if (!closed) {
      await followUpTicketError(
        interaction,
        "The ticket changed while it was closing. Please try again if it is still open.",
      );
      return;
    }
  } catch (e) {
    logger.error("Ticket: close DB update failed:", e);
    await followUpTicketError(interaction, "Failed to close. Please try again.");
    return;
  }

  // Post before locking so a stale overwrite cannot suppress the visible
  // result or remove the bot's own permission to send it.
  const noticePosted = await postClosedTicketNotice(
    ticketChannel,
    ticket.id,
    interaction.user.id,
    deleteAtSeconds,
  );
  if (!noticePosted) {
    const reopened = await appDb.reopenTicket(ticket.id).catch((e) => {
      logger.error("Ticket: failed to roll back incomplete close:", e);
      return null;
    });
    if (reopened) {
      await unlockTicketChannel(ticketChannel, ticket.opener_discord_id);
    }
    await followUpTicketError(
      interaction,
      reopened
        ? "Failed to finish closing the ticket, so it was left open. Please try again."
        : "The ticket is closed, but its Discord state could not be completed.",
    );
    return;
  }

  if (!(await lockTicketChannel(ticketChannel))) {
    await followUpTicketError(
      interaction,
      "Ticket closed, but some channel permissions could not be made read-only. Check the bot permissions, then click Close again.",
    );
  }
}

async function handleTicketReopen(
  interaction: ButtonInteraction,
  ticketId: number,
): Promise<void> {
  let ticket: appDb.Ticket | null;
  try {
    ticket = await appDb.getTicketById(ticketId);
  } catch (e) {
    logger.error("Ticket: reopen lookup failed:", e);
    await followUpTicketError(interaction, "Failed to reopen. Please try again.");
    return;
  }
  if (!ticket) {
    await followUpTicketError(interaction, "Ticket not found.");
    return;
  }
  if (ticket.channel_id !== interaction.channelId) {
    await followUpTicketError(
      interaction,
      "This button is not for this channel.",
    );
    return;
  }
  const ticketChannel = interaction.channel as TextChannel | null;
  if (!ticketChannel) {
    await followUpTicketError(interaction, "Ticket channel not found.");
    return;
  }

  await normalizeTicketChannelName(ticketChannel, ticket);

  // Another control may already have reopened this ticket. Retire the stale
  // notice and return a visible result instead of silently acknowledging it.
  if (ticket.status === "open") {
    if (!(await unlockTicketChannel(ticketChannel, ticket.opener_discord_id))) {
      await followUpTicketError(
        interaction,
        "This ticket is open, but its channel permissions could not be restored. Check the bot permissions and try again.",
      );
      return;
    }
    const retired = await retireClosedTicketNotice(interaction, ticket.id);
    await interaction.followUp({
      components: [
        retired
          ? primaryContainer("This ticket is already open.")
          : errorContainer(
              "This ticket is open, but its stale controls could not be removed.",
            ),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
    return;
  }

  if (!(await unlockTicketChannel(ticketChannel, ticket.opener_discord_id))) {
    await lockTicketChannel(ticketChannel);
    await followUpTicketError(
      interaction,
      "Failed to restore the ticket channel permissions, so it was left closed. Check the bot permissions and try again.",
    );
    return;
  }

  try {
    const reopened = await appDb.reopenTicket(ticket.id);
    if (!reopened) {
      await lockTicketChannel(ticketChannel);
      await followUpTicketError(
        interaction,
        "The ticket changed while it was reopening. Please try again.",
      );
      return;
    }
    ticket = reopened;
  } catch (e) {
    logger.error("Ticket: reopen failed:", e);
    await lockTicketChannel(ticketChannel);
    await followUpTicketError(interaction, "Failed to reopen. Please try again.");
    return;
  }

  // The header's attached Close button remains available for the next close.
  // If Discord rejects the edit, remove this separate notice so its stale
  // Reopen/Delete buttons cannot remain active.
  if (!(await retireClosedTicketNotice(interaction, ticket.id))) {
    await followUpTicketError(
      interaction,
      "The ticket reopened, but its old controls could not be disabled.",
    );
  }

  // Announce the reopen (without pinging the reopener).
  await ticketChannel
    .send({
      components: [buildReopenedNotice(`<@${interaction.user.id}>`)],
      allowedMentions: { parse: [] },
      flags: MessageFlags.IsComponentsV2,
    })
    .catch((e) => logger.error("Ticket: failed to send reopened notice:", e));
}

async function handleTicketDelete(
  interaction: ButtonInteraction,
  ticketId: number,
): Promise<void> {
  let ticket: appDb.Ticket | null;
  try {
    ticket = await appDb.getTicketById(ticketId);
  } catch (e) {
    logger.error("Ticket: delete lookup failed:", e);
    await followUpTicketError(interaction, "Failed to delete. Please try again.");
    return;
  }
  const ticketChannel = interaction.channel as TextChannel | null;
  if (!ticketChannel) {
    await followUpTicketError(interaction, "Ticket channel not found.");
    return;
  }
  if (ticket) {
    if (ticket.channel_id !== interaction.channelId) {
      await followUpTicketError(
        interaction,
        "This button is not for this channel.",
      );
      return;
    }
    if (ticket.status !== "closed") {
      await followUpTicketError(
        interaction,
        "This ticket is open and cannot be deleted.",
      );
      return;
    }
  } else if (!matchesTicketChannelId(ticketChannel, ticketId)) {
    await followUpTicketError(interaction, "Ticket not found.");
    return;
  }

  // Save the transcript to the ticket-log channel before deletion.
  try {
    const logChannel = (await interaction.guild?.channels
      .fetch(config.TICKET_LOG_CHANNEL_ID)
      .catch(() => null)) as TextChannel | null;
    if (logChannel) {
      await saveTranscriptToLog(ticketChannel, logChannel);
    }
  } catch (e) {
    logger.error("Ticket: transcript failed:", e);
  }

  try {
    await ticketChannel.delete(
      `Ticket #${ticketId} deleted by ${interaction.user.tag}`,
    );
  } catch (e) {
    if (isUnknownChannelError(e)) {
      if (ticket) await appDb.deleteTicketRow(ticket.id).catch(() => null);
      return;
    }
    logger.error("Ticket: failed to delete channel:", e);
    await followUpTicketError(
      interaction,
      "Failed to delete the ticket channel. Please check the bot permissions and try again.",
    );
    return;
  }

  if (!ticket) {
    logger.info(
      `Ticket: deleted orphaned channel ${ticketChannel.id} for ticket #${ticketId}`,
    );
    return;
  }

  try {
    await appDb.deleteTicketRow(ticket.id);
  } catch (e) {
    logger.error("Ticket: failed to delete row:", e);
  }
}

export default class ButtonInteractionEvent extends Event {
  constructor() {
    super("ButtonInteraction", "interactionCreate", false);
  }

  async execute(interaction: ButtonInteraction) {
    if (!interaction.isButton()) return;

    // I have no idea if bots can click buttons or not.. but this is here incase
    if (interaction.user.bot) return;

    // Application Hub: open (or point to) the member's application channel.
    if (interaction.customId === "app_hub_open") {
      const member = interaction.member as GuildMember | null;
      if (!member) {
        await interaction.reply({
          content: "Member information is missing. Please rejoin the server.",
          flags: MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferReply({ flags: MessageFlags.Ephemeral });

      // The whitelist database is the source of truth for membership; roles
      // can drift (manual changes, partial wipes).
      let alreadyMember = false;
      try {
        const rows = await mysql.query(
          "SELECT uuid FROM discordsrv_accounts WHERE discord = ?",
          [member.id],
        );
        alreadyMember = rows.length > 0;
      } catch (e) {
        logger.error("App hub: whitelist lookup failed:", e);
      }
      if (alreadyMember) {
        await interaction.editReply({
          components: [
            successContainer(
              "## <:Crab:1397355651822256299> You're already in!\nYou're already a member of CrabCraft, so there's no need to apply again. Enjoy your stay!",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      // If they already have a live application channel, point them to it.
      const existing = await appDb
        .getApplicationChannelByApplicant(member.id)
        .catch(() => null);
      if (existing) {
        const existingChannel = await interaction.guild?.channels
          .fetch(existing.channel_id)
          .catch(() => null);
        if (existingChannel) {
          await interaction.editReply({
            components: [
              primaryContainer(
                `You already have an open application channel: <#${existing.channel_id}>`,
              ),
            ],
            flags: MessageFlags.IsComponentsV2,
          });
          return;
        }
      }

      const channel = await createApplicationChannelFor(member);
      if (!channel) {
        await interaction.editReply({
          components: [
            errorContainer(
              "Sorry, I couldn't open an application channel. Please contact a moderator.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      await interaction.editReply({
        components: [
          primaryContainer(`Your application channel is ready: <#${channel.id}>`),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    if (interaction.customId == "apply") {
      // Only the channel's applicant can use the apply button
      const appRecord = await appDb
        .getApplicationChannelByChannelId(interaction.channelId ?? "")
        .catch(() => null);
      if (!appRecord || appRecord.applicant_id !== interaction.user.id) {
        await interaction.reply({
          components: [errorContainer("This button is not for you.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const hasPending = await appDb.hasPendingApplication(interaction.user.id);
      if (hasPending) {
        await interaction.reply({
          components: [errorContainer("You've already submitted an application. Please wait for it to be reviewed, or use the **Edit Application** button to make changes.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const currentSeason = await appDb.getCurrentSeason().catch(() => null);
      const applicationModal = new ModalBuilder()
        .setCustomId("application")
        .setTitle(`${currentSeason?.name ?? "Server"} Application`.slice(0, 45));

      const minecraftUsername = new TextInputBuilder()
        .setCustomId("minecraft-username")
        .setLabel("Minecraft Username")
        .setPlaceholder("Steve")
        .setRequired(true)
        .setStyle(TextInputStyle.Short);

      const age = new TextInputBuilder()
        .setCustomId("age")
        .setLabel("Are you 17 or older?")
        .setPlaceholder("Answer must be: Y/N")
        .setRequired(true)
        .setStyle(TextInputStyle.Short);

      const ingameVoice = new TextInputBuilder()
        .setCustomId("ingame-voice")
        .setLabel("Are you willing to speak in game?")
        .setPlaceholder("Answer must be: Y/N")
        .setRequired(true)
        .setStyle(TextInputStyle.Short);

      const joinReason = new TextInputBuilder()
        .setCustomId("join-reason")
        .setLabel("Why do you want to join CrabCraft?")
        .setPlaceholder(
          "In a sentence or two, tell us why you want to join and what you'd like to do on the server.",
        )
        .setMinLength(50)
        .setRequired(true)
        .setStyle(TextInputStyle.Paragraph);

      const favouriteWood = new TextInputBuilder()
        .setCustomId("favourite-wood")
        .setLabel("What is your favourite type of wood?")
        .setRequired(false)
        .setStyle(TextInputStyle.Short);

      const firstActionRow =
        new ActionRowBuilder<TextInputBuilder>().addComponents(age);
      const secondActionRow =
        new ActionRowBuilder<TextInputBuilder>().addComponents(
          minecraftUsername,
        );
      const thirdActionRow =
        new ActionRowBuilder<TextInputBuilder>().addComponents(ingameVoice);
      const fourthActionRow =
        new ActionRowBuilder<TextInputBuilder>().addComponents(joinReason);
      const fifthActionRow =
        new ActionRowBuilder<TextInputBuilder>().addComponents(favouriteWood);

      applicationModal.addComponents(firstActionRow);
      applicationModal.addComponents(secondActionRow);
      applicationModal.addComponents(thirdActionRow);
      applicationModal.addComponents(fourthActionRow);
      applicationModal.addComponents(fifthActionRow);

      await interaction.showModal(applicationModal);
    }

    // Season access panel: grant the season role to already-whitelisted members.
    if (interaction.customId === SEASON_PLAY_BUTTON_ID) {
      const member = interaction.member as GuildMember | null;
      if (!interaction.guild || !member) {
        await interaction.reply({
          components: [
            errorContainer("**Error!** This button can only be used in a server."),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferReply({ flags: MessageFlags.Ephemeral });

      // Whitelisted = linked in the DiscordSRV database AND in our own
      // players table.
      let srvLinked = false;
      try {
        const rows = await mysql.query(
          "SELECT uuid FROM discordsrv_accounts WHERE discord = ?",
          [interaction.user.id],
        );
        srvLinked = rows.length > 0;
      } catch (e) {
        logger.error("Season access: DiscordSRV lookup failed:", e);
      }

      const link = srvLinked
        ? await appDb.getPlayerLink(interaction.user.id).catch((e) => {
            logger.error("Season access: player link lookup failed:", e);
            return null;
          })
        : null;

      if (!srvLinked || !link?.minecraft_uuid) {
        await interaction.editReply({
          components: [
            errorContainer(
              "There was an issue locating your Minecraft account. Please open a ticket.",
            ),
            buildOpenTicketButton(),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      if (!config.CURRENT_SEASON_ROLE_ID) {
        logger.error("Season access: roles.currentSeason is not configured.");
        await interaction.editReply({
          components: [
            errorContainer(
              "**Error!** Season access isn't set up yet. Please try again later.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      try {
        await member.roles.add(config.CURRENT_SEASON_ROLE_ID);
      } catch (e) {
        logger.error("Season access: failed to add season role:", e);
        await interaction.editReply({
          components: [
            errorContainer(
              "**Error!** Something went wrong while granting access. Please try again.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      const currentSeason = await appDb.getCurrentSeason().catch(() => null);
      const seasonName = currentSeason?.name ?? "the new season";
      await interaction.editReply({
        components: [
          successContainerWithThumbnail(
            link.minecraft_username
              ? `## You're in!\n\`${link.minecraft_username}\` is confirmed for ${seasonName}.`
              : `## You're in!\nYou're confirmed for ${seasonName}.`,
            `https://render.crafty.gg/3d/bust/${link.minecraft_uuid}`,
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    if (interaction.customId === "retry-username") {
      const retryModal = new ModalBuilder()
        .setCustomId("retry-application")
        .setTitle("Retry Minecraft Username");

      const minecraftUsername = new TextInputBuilder()
        .setCustomId("minecraft-username")
        .setLabel("Minecraft Username")
        .setPlaceholder("Steve")
        .setRequired(true)
        .setStyle(TextInputStyle.Short);

      retryModal.addComponents(
        new ActionRowBuilder<TextInputBuilder>().addComponents(minecraftUsername),
      );

      await interaction.showModal(retryModal);
    }

    if (interaction.customId == "agree") {
      const agreeRecord = await appDb
        .getApplicationChannelByChannelId(interaction.channelId ?? "")
        .catch(() => null);
      if (!agreeRecord || agreeRecord.applicant_id !== interaction.user.id) {
        await interaction.reply({
          components: [errorContainer("This button is not for you.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.update({
        components: [
          coloredContainer(
            "You have **agreed** to **CrabCraft's Griefing & Stealing Policy**",
            "Green",
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });

      await appDb.setPolicyAgreed(interaction.user.id, true);
    }

    if (interaction.customId == "disagree") {
      const disagreeRecord = await appDb
        .getApplicationChannelByChannelId(interaction.channelId ?? "")
        .catch(() => null);
      if (!disagreeRecord || disagreeRecord.applicant_id !== interaction.user.id) {
        await interaction.reply({
          components: [errorContainer("This button is not for you.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.update({
        components: [
          coloredContainer(
            "You have **disagreed** to **CrabCraft's Griefing & Stealing Policy**",
            "Red",
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });

      await appDb.setPolicyAgreed(interaction.user.id, false);
    }

    // On app_accept — supports both new V2 (app_accept:username) and legacy (app_accept) formats
    if (interaction.customId === "app_accept" || interaction.customId.startsWith("app_accept:")) {
      if (
        !(interaction.member as GuildMember)?.roles.cache.has(
          config.MOD_ROLE_ID,
        )
      ) {
        await interaction.reply({
          content: `You must have the <@&${config.MOD_ROLE_ID}> role to do this.`,
          flags: MessageFlags.Ephemeral,
        });
        return;
      }

      // Acknowledge up-front: the work below (Mojang lookup, MariaDB, role add)
      // can exceed the 3s window, and we post no visible reply on success.
      await interaction.deferUpdate();

      const logChannel = await (
        interaction.member as GuildMember
      ).guild.channels.fetch(config.LOG_CHANNEL_ID).catch(() => null) as TextChannel | null;

      // Extract MC username from customId (V2) or fallback to embed fields (legacy)
      const minecraftUsername = interaction.customId.includes(":")
        ? interaction.customId.split(":")[1]
        : interaction.message.embeds[0]?.fields.find(
            (field) => field.name === "Minecraft Username",
          )?.value;

      if (!minecraftUsername) {
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** Could not find the Minecraft username from the application.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const appChannel = interaction.channel as TextChannel;
      const appRecord = await appDb
        .getApplicationChannelByChannelId(interaction.channelId ?? "")
        .catch(() => null);
      const applicantId = appRecord?.applicant_id;

      if (!applicantId) {
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** Could not determine the applicant from the channel.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const applicant = await interaction.guild?.members
        .fetch(applicantId)
        .catch(() => null);

      if (!applicant) {
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** The applicant is no longer in the server.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const application = await appDb.getLatestApplication(applicantId);
      if (!application || !application.policy_agreed) {
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** The applicant hasn't agreed to the policy yet.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const resolved = await resolveUsername(minecraftUsername);
      if (!resolved) {
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** Failed to look up the Minecraft account. Please try again later.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const UUID = resolved.uuid;

      // Atomic guard against a double-accept race (two mods clicking at the
      // same time): only the click that actually flips the application from
      // pending → accepted proceeds; the loser bails out here.
      const won = await appDb
        .acceptApplication(applicant.id, interaction.user.id)
        .catch(() => false);
      if (!won) {
        await interaction.followUp({
          components: [
            errorContainer("This application has already been processed."),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      try {
        await mysql.query(
          "INSERT INTO discordsrv_accounts (uuid, discord) VALUES (?, ?)",
          [UUID, applicantId],
        );
      } catch (error) {
        logger.error("Failed to insert whitelist record:", error);
        // Undo the accept-time status flip so a moderator can retry.
        await appDb.revertApplicationToPending(applicant.id).catch(() => null);
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** Failed to add the user to the whitelist database. Please try again.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      if (logChannel) {
        await logChannel.send({
          content: logAccept(applicant.id, minecraftUsername, `${interaction.member}`),
          allowedMentions: { parse: [] },
        }).catch((e: unknown) => logger.error("Failed to send accept log:", e));
      }

      // Membership itself lives in the whitelist database; the only role a
      // new member needs is the current season's (the same one the season
      // access button hands out).
      if (config.CURRENT_SEASON_ROLE_ID) {
        try { await applicant.roles.add(config.CURRENT_SEASON_ROLE_ID); }
        catch (e) { logger.error("Failed to add current season role:", e); }
      }

      // Claim the MC link in `players` now (not at submit), detaching
      // it from any prior owner. upsertUser handles the unique
      // constraint on players.minecraft_uuid in a transaction.
      try {
        await appDb.upsertUser({
          discordId: applicant.id,
          discordUsername: applicant.user.username,
          minecraftUsername: minecraftUsername,
          minecraftUuid: UUID,
        });
      } catch (e) {
        logger.error("Failed to upsert player on accept:", e);
      }

      captureMinecraftEvent(
        UUID,
        AnalyticsEvent.APPLICATION_RESOLVED,
        {
          outcome: "accepted",
          review_duration_seconds: Math.max(
            0,
            Math.floor(Date.now() / 1_000) - application.applied_at,
          ),
          season: application.season,
        },
        {
          dedupeKey: `${application.id}:${application.applied_at}:accepted`,
        },
      );

      // (application status was already flipped above as the race guard)

      try {
        await appChannel.send({
          content: applicationAcceptedMessage(applicant.id),
        });
      } catch (e) {
        logger.error("Failed to send acceptance message:", e);
      }

      if (logChannel) {
        await saveTranscriptToLog(appChannel, logChannel);
      }

      // Schedule deletion: the periodic + startup cleanup scans remove the
      // channel once this passes (restart-safe).
      const acceptDeleteAt = Math.floor((Date.now() + CHANNEL_DELETE_DELAY_MS) / 1000);
      await appDb
        .setApplicationChannelDeleteAfter(appChannel.id, acceptDeleteAt)
        .catch(() => null);

      try {
        const disabledRows = interaction.message.components
          .filter((row): row is (typeof interaction.message.components[number] & { type: ComponentType.ActionRow; components: any[] }) => row.type === ComponentType.ActionRow)
          .map((row) =>
            new ActionRowBuilder<ButtonBuilder>().addComponents(
              row.components.map((btn: any) => ButtonBuilder.from(btn.toJSON()).setDisabled(true)),
            ),
          );
        const otherComponents = interaction.message.components.filter(
          (row) => row.type !== ComponentType.ActionRow,
        );
        await interaction.message.edit({
          components: [...otherComponents, ...disabledRows],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Failed to disable accept/deny buttons:", e);
      }
      // No success reply — disabling the buttons + the acceptance message in
      // the channel is the confirmation. The interaction was acked via
      // deferUpdate above.
    }

    // On app_deny — supports both V2 and legacy formats
    if (interaction.customId === "app_deny" || interaction.customId.startsWith("app_deny:")) {
      if (
        !(interaction.member as GuildMember)?.roles.cache.has(
          config.MOD_ROLE_ID,
        )
      ) {
        await interaction.reply({
          content: `You must have the <@&${config.MOD_ROLE_ID}> role to do this.`,
          flags: MessageFlags.Ephemeral,
        });
        return;
      }

      // Pass MC username through the modal customId for V2
      const minecraftUsername = interaction.customId.includes(":")
        ? interaction.customId.split(":")[1]
        : "";

      await interaction.showModal(buildDenyModal(minecraftUsername));
    }

    // ── Tickets ──────────────────────────────────────────────────

    // Open: show intake modal for the chosen category
    if (interaction.customId.startsWith("ticket_open:")) {
      const category = interaction.customId.split(":")[1];
      const meta = getCategoryMeta(category);
      if (!meta) {
        await interaction.reply({
          components: [errorContainer("Unknown ticket category.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await beginTicketOpen(interaction, meta);
      return;
    }

    // Appeal infraction navigation: update only the infraction panel in-place.
    if (interaction.customId.startsWith(`${TICKET_INFRACTION_BUTTON_PREFIX}:`)) {
      const [, ticketIdRaw, pageRaw] = interaction.customId.split(":");
      const ticketId = Number(ticketIdRaw);
      const page = Number(pageRaw);
      if (!Number.isFinite(ticketId) || !Number.isFinite(page)) return;

      await interaction.deferUpdate();

      const ticket = await appDb.getTicketById(ticketId);
      if (!ticket) {
        await interaction.followUp({
          components: [errorContainer("Ticket not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }
      if (ticket.channel_id !== interaction.channelId) {
        await interaction.followUp({
          components: [errorContainer("This button is not for this channel.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const username =
        intakeString(ticket.intake, "resolved_minecraft_username") ??
        intakeString(ticket.intake, "mc_username");
      const uuid = intakeString(ticket.intake, "resolved_minecraft_uuid");
      if (!username || !uuid) {
        await interaction.followUp({
          components: [errorContainer("Infraction history is unavailable for this ticket.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const infractionInfo = await fetchPlayerInfractions(username, uuid, 25);
      const infractionMessage = buildInfractionEmbedMessage(
        ticket.id,
        infractionInfo,
        page,
      );
      if (!infractionMessage) {
        await interaction.followUp({
          components: [errorContainer("Could not update the infraction panel.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      // The nav buttons live on the infraction embed message itself, so the
      // deferred update edits exactly that message in place.
      await interaction.editReply({
        embeds: infractionMessage.embeds,
        components: infractionMessage.components,
      });
      return;
    }

    // Close: end the ticket immediately, save transcript, schedule deletion
    if (interaction.customId.startsWith("ticket_close:")) {
      const ticketId = Number(interaction.customId.split(":")[1]);
      if (!Number.isFinite(ticketId)) return;

      await interaction.deferUpdate();
      await withTicketLifecycleLock(ticketId, () =>
        handleTicketClose(interaction, ticketId),
      );
      return;
    }

    // Reopen: mods only. Restores opener access, cancels the delete schedule.
    if (interaction.customId.startsWith("ticket_reopen:")) {
      const ticketId = Number(interaction.customId.split(":")[1]);
      if (!Number.isFinite(ticketId)) return;

      const member = interaction.member as GuildMember | null;
      if (!member?.roles.cache.has(config.MOD_ROLE_ID)) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions:** only staff can reopen tickets.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferUpdate();
      await withTicketLifecycleLock(ticketId, () =>
        handleTicketReopen(interaction, ticketId),
      );
      return;
    }

    // Delete: mods only. Saves the transcript, then removes the channel + row.
    if (interaction.customId.startsWith("ticket_delete:")) {
      const ticketId = Number(interaction.customId.split(":")[1]);
      if (!Number.isFinite(ticketId)) return;

      const member = interaction.member as GuildMember | null;
      if (!member?.roles.cache.has(config.MOD_ROLE_ID)) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions:** only staff can delete tickets.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferUpdate();
      await withTicketLifecycleLock(ticketId, () =>
        handleTicketDelete(interaction, ticketId),
      );
      return;
    }

    // Edit application button (lives on the application-submitted message)
    if (interaction.customId === "edit_app") {
      const editRecord = await appDb
        .getApplicationChannelByChannelId(interaction.channelId ?? "")
        .catch(() => null);
      if (!editRecord || editRecord.applicant_id !== interaction.user.id) {
        await interaction.reply({
          components: [errorContainer("This button is not for you.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const application = await appDb.getLatestApplication(interaction.user.id);
      if (!application || application.status !== "pending") {
        await interaction.reply({
          components: [errorContainer("Your application can no longer be edited.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const editModal = new ModalBuilder()
        .setCustomId("edit-application")
        .setTitle("Edit Application");

      const minecraftUsername = new TextInputBuilder()
        .setCustomId("minecraft-username")
        .setLabel("Minecraft Username")
        .setPlaceholder("Steve")
        .setRequired(true)
        .setValue((application.minecraft_username as string) ?? "")
        .setStyle(TextInputStyle.Short);

      const age = new TextInputBuilder()
        .setCustomId("age")
        .setLabel("Are you 17 or older?")
        .setPlaceholder("Answer must be: Y/N")
        .setRequired(true)
        .setValue(application.age_met ? "Yes" : "No")
        .setStyle(TextInputStyle.Short);

      const ingameVoice = new TextInputBuilder()
        .setCustomId("ingame-voice")
        .setLabel("Are you willing to speak in game?")
        .setPlaceholder("Answer must be: Y/N")
        .setRequired(true)
        .setValue(application.voice_chat ? "Yes" : "No")
        .setStyle(TextInputStyle.Short);

      const joinReason = new TextInputBuilder()
        .setCustomId("join-reason")
        .setLabel("Why do you want to join CrabCraft?")
        .setPlaceholder(
          "In a sentence or two, tell us why you want to join and what you'd like to do on the server.",
        )
        .setMinLength(50)
        .setRequired(true)
        .setValue((application.join_reason as string) ?? "")
        .setStyle(TextInputStyle.Paragraph);

      const favouriteWood = new TextInputBuilder()
        .setCustomId("favourite-wood")
        .setLabel("What is your favourite type of wood?")
        .setRequired(false)
        .setValue((application.favourite_wood as string) ?? "")
        .setStyle(TextInputStyle.Short);

      editModal.addComponents(
        new ActionRowBuilder<TextInputBuilder>().addComponents(age),
        new ActionRowBuilder<TextInputBuilder>().addComponents(minecraftUsername),
        new ActionRowBuilder<TextInputBuilder>().addComponents(ingameVoice),
        new ActionRowBuilder<TextInputBuilder>().addComponents(joinReason),
        new ActionRowBuilder<TextInputBuilder>().addComponents(favouriteWood),
      );

      await interaction.showModal(editModal);
    }
  }
}
