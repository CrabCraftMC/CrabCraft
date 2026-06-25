import Event from "../structures/Event.js";
import config from "../utils/config.js";
import {
  errorContainer,
  primaryContainer,
  primaryContainerWithThumbnail,
  logAutoReject,
  logDeny,
  logAccept,
} from "../utils/embeds.js";
import logger from "../utils/logger.js";
import {
  type GuildMember,
  type ModalSubmitInteraction,
  type TextChannel,
  ButtonBuilder,
  ButtonStyle,
  ActionRowBuilder,
  ChannelType,
  ComponentType,
  ContainerBuilder,
  PermissionFlagsBits,
  SectionBuilder,
  TextDisplayBuilder,
  ThumbnailBuilder,
  MessageFlags,
  ThreadAutoArchiveDuration,
  type ThreadChannel,
} from "discord.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import type { ShopDeedApplication } from "../utils/appDb.js";
import { storeRetry, getRetry } from "../utils/retryStore.js";
import { resolveUsername } from "../utils/mojang.js";
import { CHANNEL_DELETE_DELAY_MS } from "../utils/constants.js";
import { saveTranscriptToLog } from "../utils/transcript.js";
import {
  fetchPlayerInfractions,
  type TicketInfractionInfo,
} from "../utils/infractions.js";
import {
  buildChannelName,
  buildStaffButtons,
  buildTicketHeader,
  getCategoryMeta,
  getPlayerInfo,
} from "../utils/ticket.js";
import {
  SHOP_DEED_FIELDS,
  buildDisabledShopDeedStaffButtons,
  buildShopDeedPanel,
  buildShopDeedPanelButton,
  buildShopDeedDecisionNotice,
  buildShopDeedHeader,
  buildShopDeedStaffButtons,
  buildShopDeedThreadName,
  type ShopDeedFeedbackDecision,
} from "../utils/shopDeed.js";

const ACCEPTED_VALUES = [
  "y",
  "yes",
  "yeah",
  "yep",
  "sure",
  "ok",
  "okay",
  "accept",
  "accepted",
  "true",
  "1",
  "positive",
];

// Explicitly negative answers to "Are you 17 or older?". These — together with
// any number below 17 — are the only answers that trigger an automatic denial.
// Anything else (e.g. "Yerp" or junk like "sdfjhgsdf") is left for a human to
// review rather than auto-rejected.
const DENIED_VALUES = [
  "n",
  "no",
  "nope",
  "nah",
  "false",
  "negative",
];

async function restickShopDeedPanel(
  interaction: ModalSubmitInteraction,
  channel: TextChannel,
): Promise<void> {
  try {
    if (interaction.isFromMessage()) {
      await interaction.message.delete().catch(() => null);
    }

    await channel.send({
      components: [buildShopDeedPanel(), buildShopDeedPanelButton()],
      flags: MessageFlags.IsComponentsV2,
    });
  } catch (e) {
    logger.error("Shop deed: failed to refresh sticky panel:", e);
  }
}

/** Build a retry button row for invalid usernames. */
function retryButtonRow(customId: string) {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(customId)
      .setLabel("Retry Username")
      .setStyle(ButtonStyle.Primary),
  );
}

/**
 * Buttons shown on the application-submitted message: Accept / Deny (staff)
 * and Edit Application (applicant). The MC username rides along on the
 * accept/deny custom ids.
 */
function buildApplicationActionRow(
  minecraftUsername: string,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`app_accept:${minecraftUsername}`)
      .setLabel("Accept")
      .setStyle(ButtonStyle.Success),
    new ButtonBuilder()
      .setCustomId(`app_deny:${minecraftUsername}`)
      .setLabel("Deny")
      .setStyle(ButtonStyle.Danger),
    new ButtonBuilder()
      .setCustomId("edit_app")
      .setLabel("Edit Application")
      .setStyle(ButtonStyle.Secondary)
      .setEmoji("✏️"),
  );
}

export default class ModalInteractionEvent extends Event {
  constructor() {
    super("ModalInteraction", "interactionCreate", false);
  }

  async execute(interaction: ModalSubmitInteraction) {
    if (!interaction.isModalSubmit()) return;

    // ── Full Application ──────────────────────────────────────────────
    if (interaction.customId === "application") {
      const age = interaction.fields
        .getTextInputValue("age")
        .toLocaleLowerCase();
      const minecraftUsername =
        interaction.fields.getTextInputValue("minecraft-username");
      const ingameVoice = interaction.fields
        .getTextInputValue("ingame-voice")
        .toLocaleLowerCase();
      const joinReason = interaction.fields.getTextInputValue("join-reason");
      const favouriteWood =
        interaction.fields.getTextInputValue("favourite-wood");

      if (!interaction.member) {
        await interaction.reply({
          components: [
            errorContainer(
              "Member information is missing. Please rejoin the server.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      const logChannel = await (
        interaction.member as GuildMember
      ).guild.channels.fetch(config.LOG_CHANNEL_ID).catch(() => null) as TextChannel | null;

      interaction.message?.delete().catch(() => null);

      // Age gate: only auto-deny when the applicant explicitly says no (a
      // negative answer) or gives a number below 17. Anything else — including
      // ambiguous or junk answers like "Yerp" or "sdfjhgsdf" — passes the gate
      // and is left for a human to review.
      const ageNumber = parseInt(age, 10);
      const isUnderage =
        DENIED_VALUES.includes(age) ||
        (Number.isFinite(ageNumber) && ageNumber < 17);
      if (isUnderage) {
        await interaction.reply({
          components: [
            errorContainer(
              "**Sorry**, you must be 17 or older to join CrabCraft.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        if (logChannel) {
          await logChannel.send({
            components: [
              logAutoReject(
                interaction.user.id,
                "Age requirement not met (must be 17+)",
              ),
            ],
            flags: MessageFlags.IsComponentsV2,
          }).catch(() => null);
        }
        return;
      }

      const resolved = await resolveUsername(minecraftUsername);
      if (!resolved) {
        storeRetry(interaction.user.id, {
          type: "full",
          age,
          ingameVoice,
          joinReason,
          favouriteWood,
        });
        await interaction.reply({
          components: [
            errorContainer(
              `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.\n\nClick below to try a different username.`,
            ),
            retryButtonRow("retry-username"),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      await this.processFullApplication(
        interaction,
        logChannel,
        minecraftUsername,
        resolved.uuid,
        age,
        ingameVoice,
        joinReason,
        favouriteWood,
      );
      return;
    }

    // ── Full Application Retry ────────────────────────────────────────
    if (interaction.customId === "retry-application") {
      interaction.message?.delete().catch(() => null);

      const minecraftUsername =
        interaction.fields.getTextInputValue("minecraft-username");
      const stored = getRetry(interaction.user.id);

      if (!stored || stored.type !== "full") {
        await interaction.reply({
          components: [
            errorContainer(
              "Your retry session has expired. Please start a new application.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      const logChannel = await (
        interaction.member as GuildMember
      ).guild.channels.fetch(config.LOG_CHANNEL_ID).catch(() => null) as TextChannel | null;

      const resolved = await resolveUsername(minecraftUsername);
      if (!resolved) {
        // Re-store so they can retry again
        storeRetry(interaction.user.id, stored);
        await interaction.reply({
          components: [
            errorContainer(
              `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.\n\nClick below to try again.`,
            ),
            retryButtonRow("retry-username"),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      await this.processFullApplication(
        interaction,
        logChannel,
        minecraftUsername,
        resolved.uuid,
        stored.age,
        stored.ingameVoice,
        stored.joinReason,
        stored.favouriteWood,
      );
      return;
    }

    // ── Fast Application ──────────────────────────────────────────────
    if (interaction.customId === "fast-application") {
      const minecraftUsername =
        interaction.fields.getTextInputValue("minecraft-username");

      const resolved = await resolveUsername(minecraftUsername);
      if (!resolved) {
        storeRetry(interaction.user.id, { type: "fast" });
        await interaction.reply({
          components: [
            errorContainer(
              `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.\n\nClick below to try a different username.`,
            ),
            retryButtonRow("retry-fast-username"),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await this.processFastApplication(
        interaction,
        minecraftUsername,
        resolved.uuid,
      );
      return;
    }

    // ── Fast Application Retry ────────────────────────────────────────
    if (interaction.customId === "retry-fast-application") {
      interaction.message?.delete().catch(() => null);

      const minecraftUsername =
        interaction.fields.getTextInputValue("minecraft-username");
      const stored = getRetry(interaction.user.id);

      if (!stored || stored.type !== "fast") {
        await interaction.reply({
          components: [
            errorContainer(
              "Your retry session has expired. Please start a new application.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const resolved = await resolveUsername(minecraftUsername);
      if (!resolved) {
        storeRetry(interaction.user.id, stored);
        await interaction.reply({
          components: [
            errorContainer(
              `**Sorry**, the provided username: \`${minecraftUsername}\` is not a valid Minecraft Java username.\n\nClick below to try again.`,
            ),
            retryButtonRow("retry-fast-username"),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await this.processFastApplication(
        interaction,
        minecraftUsername,
        resolved.uuid,
      );
      return;
    }

    // ── Edit Application ──────────────────────────────────────────────
    if (interaction.customId === "edit-application") {
      const minecraftUsername = interaction.fields.getTextInputValue("minecraft-username");
      const age = interaction.fields.getTextInputValue("age").toLocaleLowerCase();
      const ingameVoice = interaction.fields.getTextInputValue("ingame-voice").toLocaleLowerCase();
      const joinReason = interaction.fields.getTextInputValue("join-reason");
      const favouriteWood = interaction.fields.getTextInputValue("favourite-wood");

      // Verify application is still pending
      const application = await appDb.getLatestApplication(interaction.user.id);
      if (!application || application.status !== "pending") {
        await interaction.reply({
          components: [errorContainer("Your application can no longer be edited.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      // Validate MC username
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

      // Update database. The MC link in `players` is claimed at accept
      // time only — here we just refresh the application row and keep
      // the player's discord_username current.
      try {
        await appDb.updateApplication(interaction.user.id, {
          minecraftUsername: minecraftUsername,
          minecraftUuid: resolved.uuid,
          ageMet: ACCEPTED_VALUES.includes(age) || parseInt(age, 10) >= 17,
          voiceChat: ACCEPTED_VALUES.includes(ingameVoice),
          joinReason: joinReason,
          favouriteWood: favouriteWood || undefined,
        });
        await appDb.upsertUser({
          discordId: interaction.user.id,
          discordUsername: interaction.user.username,
        });
      } catch (e) {
        logger.error("Failed to update application in database:", e);
      }

      // Re-render the application-submitted message (the one this modal was
      // opened from) with the updated details. Editing it is the ack.
      const fields = [
        `**Are you 17 or older?**\n${age}`,
        `**Are you willing to speak in game?**\n${ingameVoice}`,
        `**Why do you want to join CrabCraft?**\n${joinReason}`,
      ];
      if (favouriteWood)
        fields.push(`**What is your favourite type of wood?**\n${favouriteWood}`);

      const updatedContainer = new ContainerBuilder()
        .addSectionComponents(
          new SectionBuilder()
            .addTextDisplayComponents((td) =>
              td.setContent(
                `## Application Submitted (Edited)\n**Minecraft Username**\n${minecraftUsername}`,
              ),
            )
            .setThumbnailAccessory(
              new ThumbnailBuilder().setURL(
                `https://api.mineatar.io/body/full/${resolved.uuid}?scale=12`,
              ),
            ),
        )
        .addTextDisplayComponents((td) => td.setContent(fields.join("\n\n")));

      if (interaction.isFromMessage()) {
        await interaction.update({
          components: [
            updatedContainer,
            buildApplicationActionRow(minecraftUsername),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } else {
        await interaction.reply({
          components: [primaryContainer("Your application has been updated.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
      }
      return;
    }

    // ── Shop Deed Request Modal ─────────────────────────────────────
    if (interaction.customId === "shop_deed_modal") {
      if (!interaction.guild || !interaction.guildId) {
        await interaction.reply({
          components: [
            errorContainer("Shop deed requests can only be created in a server."),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const parentChannel = interaction.channel as TextChannel | null;
      if (!parentChannel || parentChannel.type !== ChannelType.GuildText) {
        await interaction.reply({
          components: [
            errorContainer("Shop deed requests must be created from a text channel."),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferReply({ flags: MessageFlags.Ephemeral });

      const eligible = await appDb
        .isWhitelistedForCurrentSeason(interaction.user.id)
        .catch((e) => {
          logger.error("Shop deed: eligibility check failed:", e);
          return false;
        });
      if (!eligible) {
        await interaction.editReply({
          components: [
            errorContainer(
              "Shop deed requests are only available to players accepted for the current season.",
            ),
          ],
        });
        return;
      }

      const intake: Record<string, string> = {};
      for (const field of SHOP_DEED_FIELDS) {
        try {
          const value = interaction.fields.getTextInputValue(field.id).trim();
          if (value.length > 0) intake[field.id] = value;
        } catch {
          // Field missing - handled by required field validation below.
        }
      }

      const missingRequired = SHOP_DEED_FIELDS
        .filter((field) => field.required && !intake[field.id])
        .map((field) => field.display);
      if (missingRequired.length > 0) {
        await interaction.editReply({
          components: [
            errorContainer(
              `Missing required field: ${missingRequired.join(", ")}.`,
            ),
          ],
        });
        return;
      }

      let thread: ThreadChannel;
      try {
        thread = await parentChannel.threads.create({
          name: buildShopDeedThreadName(
            intake.shop_name,
            interaction.user.username,
          ),
          autoArchiveDuration: ThreadAutoArchiveDuration.OneWeek,
          type: ChannelType.PublicThread,
          reason: `Shop deed request by ${interaction.user.tag}`,
        });
      } catch (e) {
        logger.error("Shop deed: failed to create thread:", e);
        await interaction.editReply({
          components: [
            errorContainer(
              "Failed to create the shop deed thread. Please try again or contact a moderator.",
            ),
          ],
        });
        return;
      }

      let application: ShopDeedApplication;
      try {
        application = await appDb.createShopDeedApplication({
          threadId: thread.id,
          channelId: parentChannel.id,
          guildId: interaction.guildId,
          applicantDiscordId: interaction.user.id,
          applicantDiscordUsername: interaction.user.username,
          shopName: intake.shop_name,
          shopDescription: intake.shop_description,
          goodsServices: intake.goods_services,
          location: intake.location ?? null,
        });
      } catch (e) {
        logger.error("Shop deed: failed to persist request:", e);
        await thread.delete("Shop deed request persistence failed").catch(() => null);
        await interaction.editReply({
          components: [
            errorContainer(
              "Failed to save your shop deed request. Please try again in a moment.",
            ),
          ],
        });
        return;
      }

      try {
        await thread.send({
          content: `<@${interaction.user.id}>`,
          allowedMentions: {
            parse: [],
            users: [interaction.user.id],
            roles: [],
          },
        });
        await thread.send({
          components: [
            buildShopDeedHeader(application),
            buildShopDeedStaffButtons(application.id),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Shop deed: failed to send thread header:", e);
        await thread.send({
          content: `Shop deed request #${String(application.id).padStart(4, "0")} was opened by ${interaction.user.username}, but the rich summary could not be posted automatically.`,
          allowedMentions: { parse: [] },
        }).catch((fallbackError) => {
          logger.error("Shop deed: failed to send fallback header:", fallbackError);
        });
      }

      await interaction.editReply({
        components: [
          primaryContainer(
            `Your shop deed request is ready in <#${thread.id}>.`,
          ),
        ],
      });
      await restickShopDeedPanel(interaction, parentChannel);
      return;
    }

    // ── Shop Deed Decision Modal ────────────────────────────────────
    if (interaction.customId.startsWith("shop_deed_decision:")) {
      const [, rawDecision, rawId] = interaction.customId.split(":");
      const decision = rawDecision as ShopDeedFeedbackDecision;
      const applicationId = Number(rawId);
      const member = interaction.member as GuildMember | null;

      if (!member?.roles.cache.has(config.MOD_ROLE_ID)) {
        await interaction.reply({
          content: `You must have the <@&${config.MOD_ROLE_ID}> role to do this.`,
          flags: MessageFlags.Ephemeral,
        });
        return;
      }

      if (
        (decision !== "changes_requested" && decision !== "rejected") ||
        !Number.isFinite(applicationId)
      ) {
        await interaction.reply({
          components: [errorContainer("Shop deed request not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const reason = interaction.fields.getTextInputValue("reason").trim();
      if (!reason) {
        await interaction.reply({
          components: [errorContainer("A decision reason is required.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      if (interaction.isFromMessage()) {
        await interaction.deferUpdate();
      } else {
        await interaction.deferReply({ flags: MessageFlags.Ephemeral });
      }

      const application = await appDb.getShopDeedApplicationById(applicationId);
      if (!application || application.thread_id !== interaction.channelId) {
        await interaction.followUp({
          components: [errorContainer("Shop deed request not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const resolved = await appDb.resolveShopDeedApplication(
        application.id,
        decision,
        reason,
        interaction.user.id,
      );
      if (!resolved) {
        await interaction.followUp({
          components: [
            errorContainer("This shop deed request has already been processed."),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      if (interaction.message) {
        try {
          await interaction.message.edit({
            components: [
              buildShopDeedHeader(resolved),
              decision === "rejected"
                ? buildDisabledShopDeedStaffButtons(resolved.id)
                : buildShopDeedStaffButtons(resolved.id),
            ],
            flags: MessageFlags.IsComponentsV2,
          });
        } catch (e) {
          logger.error("Shop deed: failed to disable decision buttons:", e);
        }
      }

      try {
        await (interaction.channel as ThreadChannel | null)?.setName(
          buildShopDeedThreadName(
            resolved.shop_name,
            resolved.applicant_discord_username,
            resolved.status,
          ),
          `Shop deed ${resolved.status}`,
        );
      } catch (e) {
        logger.error("Shop deed: failed to rename reviewed thread:", e);
      }

      const thread = interaction.channel as TextChannel | null;
      if (thread) {
        try {
          await thread.send({
            components: [
              buildShopDeedDecisionNotice(
                resolved,
                decision,
                interaction.user.username,
                reason,
              ),
            ],
            flags: MessageFlags.IsComponentsV2,
          });
        } catch (e) {
          logger.error("Shop deed: failed to send decision notice:", e);
        }
      }

      if (!interaction.isFromMessage()) {
        await interaction.editReply({
          components: [primaryContainer("Shop deed request updated.")],
        });
      }
      return;
    }

    // ── Ticket Intake Modal ──────────────────────────────────────────
    if (interaction.customId.startsWith("ticket_modal:")) {
      const category = interaction.customId.split(":")[1];
      const meta = getCategoryMeta(category);
      if (!meta) {
        await interaction.reply({
          components: [errorContainer("Unknown ticket category.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      // Re-check the rate limit (the user might have opened another since clicking).
      try {
        const openCount = await appDb.countOpenTicketsForUserAndCategory(
          interaction.user.id,
          meta.category,
        );
        if (openCount >= appDb.MAX_OPEN_TICKETS_PER_CATEGORY) {
          await interaction.reply({
            components: [
              errorContainer(
                `**You already have an open ${meta.label} ticket.** Please use your existing ticket or close it first.`,
              ),
            ],
            flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
          });
          return;
        }
      } catch (e) {
        logger.error("Ticket: rate-limit re-check failed:", e);
      }

      await interaction.deferReply({ flags: MessageFlags.Ephemeral });

      // Collect intake field values.
      const intake: Record<string, string> = {};
      for (const field of meta.fields) {
        try {
          const value = interaction.fields.getTextInputValue(field.id);
          if (value && value.trim().length > 0) intake[field.id] = value.trim();
        } catch {
          // Field missing — skip.
        }
      }

      const missingRequired = meta.fields
        .filter((field) => field.required && !intake[field.id])
        .map((field) => field.display);
      if (missingRequired.length > 0) {
        await interaction.editReply({
          components: [
            errorContainer(
              `**Missing required field.** Please reopen the form and complete: ${missingRequired.join(", ")}.`,
            ),
          ],
        });
        return;
      }

      let ticketInfractionInfo: TicketInfractionInfo | null = null;
      if (meta.category === "appeal" && intake.mc_username) {
        const submittedUsername = intake.mc_username;
        const resolved = await resolveUsername(submittedUsername);
        if (resolved) {
          intake.mc_username = resolved.name;
          intake.resolved_minecraft_username = resolved.name;
          intake.resolved_minecraft_uuid = resolved.uuid;
          ticketInfractionInfo = await fetchPlayerInfractions(
            resolved.name,
            resolved.uuid,
            10,
          );
        } else {
          ticketInfractionInfo = {
            username: submittedUsername,
            uuid: null,
            infractions: null,
            error: "Could not resolve the submitted Minecraft username.",
          };
        }
      }

      const subject =
        meta.category === "general" && intake.subject
          ? intake.subject
          : null;

      // Resolve the ticket category. Each ticket is its own text channel
      // created beneath this category.
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

      // Create the dedicated text channel for this ticket. Permission overwrites
      // hide the channel from @everyone, grant the opener access, and grant
      // the moderator role full access.
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

      // Pull player context first so we can persist the MC link on the ticket row.
      const player = await getPlayerInfo(interaction.user.id, interaction.user.tag);

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
          subject,
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

      try {
        await ticketChannel.send({
          content: `<@${interaction.user.id}>`,
          allowedMentions: {
            parse: [],
            users: [interaction.user.id],
            roles: [],
          },
        });
        await ticketChannel.send({
          components: [
            buildTicketHeader(ticket, meta, player, intake, ticketInfractionInfo),
            buildStaffButtons(ticket.id),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Ticket: failed to send opening message:", e);
        await ticketChannel.send({
          content:
            `Ticket #${String(ticket.id).padStart(4, "0")} was opened by <@${interaction.user.id}> for ${meta.label}, but the rich ticket summary could not be posted automatically.`,
        }).catch((fallbackError) => {
          logger.error("Ticket: failed to send fallback opening message:", fallbackError);
        });
      }

      await interaction.editReply({
        components: [
          primaryContainer(
            `### ${meta.emoji} Ticket Opened\nYour **${meta.label}** ticket is ready in <#${ticketChannel.id}>.`,
          ),
        ],
      });
      return;
    }

    // ── Deny Modal — supports V2 (deny_modal:username) and legacy (deny_modal) ──
    if (
      interaction.customId === "deny_modal" ||
      interaction.customId.startsWith("deny_modal:")
    ) {
      // Acknowledge without a visible reply; disabling the buttons + the
      // denial message in the channel is the confirmation. (The deny modal is
      // always shown from the Deny button, so it is from a message.)
      if (interaction.isFromMessage()) await interaction.deferUpdate();

      const reason =
        interaction.fields.getTextInputValue("deny_reason") ||
        "No reason provided";

      const denyContainer = errorContainer(
        `## Application Denied\nYour application has been **denied**.\n**Reason**\n\`\`\`${reason}\`\`\`\n-# Channel will be deleted in 12 hours`,
      );

      const appChannel = interaction.channel as TextChannel;
      const denyRecord = await appDb
        .getApplicationChannelByChannelId(interaction.channelId ?? "")
        .catch(() => null);

      if (!interaction.guild) return;
      const logChannel = await interaction.guild.channels
        .fetch(config.LOG_CHANNEL_ID)
        .catch(() => null) as TextChannel | null;

      if (interaction.message) {
        try {
          const disabledRows = interaction.message.components
            .filter(
              (
                row,
              ): row is (typeof interaction.message.components)[number] & {
                type: ComponentType.ActionRow;
                components: any[];
              } => row.type === ComponentType.ActionRow,
            )
            .map((row) =>
              new ActionRowBuilder<ButtonBuilder>().addComponents(
                row.components.map((btn: any) =>
                  ButtonBuilder.from(btn.toJSON()).setDisabled(true),
                ),
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
      }

      const applicantId = denyRecord?.applicant_id;
      try {
        await appChannel.send({
          components: [
            ...(applicantId
              ? [new TextDisplayBuilder().setContent(`<@${applicantId}>`)]
              : []),
            denyContainer,
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Failed to send denial message:", e);
      }

      // Get MC username from modal customId (V2) or fallback to embed fields (legacy)
      const minecraftUsername = interaction.customId.includes(":")
        ? interaction.customId.split(":")[1]
        : interaction.message?.embeds[0]?.fields.find(
            (field) => field.name === "Minecraft Username",
          )?.value;

      if (logChannel) {
        await logChannel.send({
          components: [logDeny(applicantId ?? "unknown", minecraftUsername, reason, `${interaction.user}`)],
          flags: MessageFlags.IsComponentsV2,
        }).catch(() => null);
      }

      // Update application status in database
      if (applicantId) {
        try {
          await appDb.denyApplication(applicantId, reason, interaction.user.id);
        } catch (e) {
          logger.error("Failed to update deny status in database:", e);
        }
      }

      if (logChannel) {
        await saveTranscriptToLog(appChannel, logChannel, `denied by ${interaction.user.tag}`).catch(() => null);
      }

      // Schedule deletion: the periodic + startup cleanup scans remove the
      // channel once this passes (restart-safe).
      const denyDeleteAt = Math.floor((Date.now() + CHANNEL_DELETE_DELAY_MS) / 1000);
      if (denyRecord) {
        await appDb
          .setApplicationChannelDeleteAfter(denyRecord.channel_id, denyDeleteAt)
          .catch(() => null);
      } else {
        // No tracking row (anomaly) — fall back to a direct timed deletion so
        // the channel isn't orphaned (the cleanup scan only sees rows).
        logger.warn(
          `Deny: no application_channels row for channel ${appChannel.id}; scheduling direct deletion.`,
        );
        setTimeout(() => {
          appChannel.delete().catch(() => null);
        }, CHANNEL_DELETE_DELAY_MS);
      }
      return;
    }
  }

  // ── Shared: process a validated full application ───────────────────
  private async processFullApplication(
    interaction: ModalSubmitInteraction,
    logChannel: TextChannel | null,
    minecraftUsername: string,
    UUID: string,
    age: string,
    ingameVoice: string,
    joinReason: string,
    favouriteWood: string,
  ) {
    // Acknowledge the modal up-front (ephemeral). The submitted + policy
    // messages are posted as fresh channel messages, then this ack is cleared.
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const channel = interaction.channel as TextChannel;

    // Cancel any stale pending applications (e.g. user left and rejoined)
    try {
      await appDb.cancelPendingApplications(interaction.user.id);
    } catch (e) {
      logger.error("Failed to cancel pending applications:", e);
    }

    // Clean up any stale whitelist record from a previous application
    try {
      await mysql.query("DELETE FROM discordsrv_accounts WHERE discord = ?", [
        interaction.user.id,
      ]);
    } catch (e) {
      logger.error("Failed to clean up stale whitelist record:", e);
    }

    // Check MariaDB (already whitelisted)
    const mysqlRows = await mysql.query(
      "SELECT * FROM discordsrv_accounts WHERE uuid = ? OR discord = ?",
      [UUID, interaction.user.id],
    );

    if (mysqlRows.length > 0) {
      await interaction.editReply({
        components: [
          errorContainer("**Error!** You are already a member of CrabCraft."),
        ],
        flags: MessageFlags.IsComponentsV2,
      });
      if (logChannel) {
        await logChannel.send({
          components: [logAutoReject(interaction.user.id, "Already a member")],
          flags: MessageFlags.IsComponentsV2,
        }).catch(() => null);
      }
      return;
    }

    const fields = [
      `**Are you 17 or older?**\n${age}`,
      `**Are you willing to speak in game?**\n${ingameVoice}`,
      `**Why do you want to join CrabCraft?**\n${joinReason}`,
    ];
    if (favouriteWood)
      fields.push(`**What is your favourite type of wood?**\n${favouriteWood}`);

    const submittedContainer = new ContainerBuilder()
      .addSectionComponents(
        new SectionBuilder()
          .addTextDisplayComponents((td) =>
            td.setContent(
              `## Application Submitted\n**Minecraft Username**\n${minecraftUsername}`,
            ),
          )
          .setThumbnailAccessory(
            new ThumbnailBuilder().setURL(
              `https://api.mineatar.io/body/full/${UUID}?scale=12`,
            ),
          ),
      )
      .addTextDisplayComponents((td) => td.setContent(fields.join("\n\n")));

    // Persist to database first; only post the application + policy if it
    // saved. The MC link in `players` is claimed at accept time only.
    const currentSeason = await appDb.getCurrentSeason().catch(() => null);
    let persisted = false;
    try {
      await appDb.upsertUser({
        discordId: interaction.user.id,
        discordUsername: interaction.user.username,
      });
      await appDb.createApplication({
        discordId: interaction.user.id,
        discordUsername: interaction.user.username,
        minecraftUsername: minecraftUsername,
        minecraftUuid: UUID,
        ageMet: ACCEPTED_VALUES.includes(age) || parseInt(age, 10) >= 17,
        voiceChat: ACCEPTED_VALUES.includes(ingameVoice),
        joinReason: joinReason,
        favouriteWood: favouriteWood || undefined,
        season: currentSeason?.id ?? null,
      });
      persisted = true;
    } catch (e) {
      logger.error("Failed to persist application to database:", e);
    }

    if (!persisted) {
      await channel
        .send({
          components: [
            errorContainer(
              "**Error!** Failed to save your application. Please contact a moderator.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        })
        .catch((e) => logger.error("Failed to send persist-failure notice:", e));
      await interaction.deleteReply().catch(() => null);
      return;
    }

    // Application-submitted message (fresh) with Accept / Deny / Edit.
    const submittedMessage = await channel
      .send({
        components: [
          submittedContainer,
          buildApplicationActionRow(minecraftUsername),
        ],
        flags: MessageFlags.IsComponentsV2,
      })
      .catch((e) => {
        logger.error("Failed to send application message:", e);
        return null;
      });

    // Policy message — sent as a reply to the application-submitted message.
    const policyContainer = primaryContainer(
      "## CrabCraft's Griefing & Stealing Policy\n**Just before we review your application, we need to ensure that you understand our policy on stealing.**\n\nWe have a zero tolerance policy towards stealing. If you steal from another player, you will be banned from the server.\nAll block interactions are logged, and all players are able to look into chest logs. This means any stealing will be traced back to the offending player.",
    );
    const policyRow = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setCustomId("agree")
        .setLabel("I Agree")
        .setStyle(ButtonStyle.Success),
      new ButtonBuilder()
        .setCustomId("disagree")
        .setLabel("I Disagree")
        .setStyle(ButtonStyle.Danger),
    );

    await channel
      .send({
        components: [policyContainer, policyRow],
        flags: MessageFlags.IsComponentsV2,
        ...(submittedMessage
          ? {
              reply: {
                messageReference: submittedMessage.id,
                failIfNotExists: false,
              },
            }
          : {}),
      })
      .catch((e) => logger.error("Failed to send policy message:", e));

    await interaction.deleteReply().catch(() => null);
  }

  // ── Shared: process a validated fast application ───────────────────
  private async processFastApplication(
    interaction: ModalSubmitInteraction,
    minecraftUsername: string,
    UUID: string,
  ) {
    const logChannel = await (
      interaction.member as GuildMember
    ).guild.channels.fetch(config.LOG_CHANNEL_ID).catch(() => null) as TextChannel | null;

    // Clean up any stale whitelist record from a previous application
    try {
      await mysql.query("DELETE FROM discordsrv_accounts WHERE discord = ?", [
        interaction.user.id,
      ]);
    } catch (e) {
      logger.error("Failed to clean up stale whitelist record:", e);
    }

    const rows = await mysql.query(
      "SELECT * FROM discordsrv_accounts WHERE uuid = ? OR discord = ?",
      [UUID, interaction.user.id],
    );

    if (rows.length > 0) {
      await interaction.reply({
        components: [
          errorContainer("**Error!** You are already a member of CrabCraft."),
        ],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
      if (logChannel) {
        await logChannel.send({
          components: [logAutoReject(interaction.user.id, "Already a member")],
          flags: MessageFlags.IsComponentsV2,
        }).catch(() => null);
      }
      return;
    }

    const memberRole = interaction.guild?.roles.cache.get(
      config.MEMBER_ROLE_ID,
    );

    if (memberRole) {
      const member = interaction.guild?.members.cache.get(interaction.user.id);
      await member?.roles.add(memberRole).catch((e: unknown) => logger.error("Failed to add member role:", e));
    }

    await interaction.reply({
      components: [
        primaryContainerWithThumbnail(
          "## Username Whitelisted\nYour username has been **whitelisted**!\n\nDon't forget to setup Simple Voice Chat before the release! You can find a guide [here](https://wiki.crabcraft.net/Setup_Guide)",
          `https://api.mineatar.io/body/full/${UUID}?scale=12`,
        ),
      ],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });

    try {
      await mysql.query(
        "INSERT INTO discordsrv_accounts (uuid, discord) VALUES (?, ?)",
        [UUID, interaction.user.id],
      );
    } catch (e) {
      logger.error("Fast application: failed to insert whitelist record:", e);
    }

    if (logChannel) {
      await logChannel.send({
        components: [logAccept(interaction.user.id, minecraftUsername, UUID)],
        flags: MessageFlags.IsComponentsV2,
      }).catch(() => null);
    }

    // Persist to database (upsert user + create accepted application)
    try {
      const currentSeason = await appDb.getCurrentSeason().catch(() => null);
      await appDb.upsertUser({
        discordId: interaction.user.id,
        discordUsername: interaction.user.username,
        minecraftUsername: minecraftUsername,
        minecraftUuid: UUID,
      });
      await appDb.createApplication({
        discordId: interaction.user.id,
        discordUsername: interaction.user.username,
        minecraftUsername: minecraftUsername,
        minecraftUuid: UUID,
        ageMet: true,
        voiceChat: true,
        season: currentSeason?.id ?? null,
      });
      await appDb.acceptApplication(interaction.user.id, interaction.user.id);
    } catch (e) {
      logger.error("Fast application: failed to persist to database:", e);
    }
  }
}
