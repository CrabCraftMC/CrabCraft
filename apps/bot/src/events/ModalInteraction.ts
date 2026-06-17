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
  type ThreadChannel,
  ButtonBuilder,
  ButtonStyle,
  ActionRowBuilder,
  ChannelType,
  ComponentType,
  ContainerBuilder,
  PermissionFlagsBits,
  SectionBuilder,
  ThumbnailBuilder,
  MessageFlags,
} from "discord.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import { storeRetry, getRetry } from "../utils/retryStore.js";
import { resolveUsername } from "../utils/mojang.js";
import { CHANNEL_DELETE_DELAY_MS } from "../utils/constants.js";
import { saveTranscriptToLog } from "../utils/transcript.js";
import {
  buildChannelName,
  buildStaffButtons,
  buildTicketHeader,
  getCategoryMeta,
  getPlayerInfo,
} from "../utils/ticket.js";

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

/** Build a retry button row for invalid usernames. */
function retryButtonRow(customId: string) {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(customId)
      .setLabel("Retry Username")
      .setStyle(ButtonStyle.Primary),
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

      if (parseInt(age) < 15 && !ACCEPTED_VALUES.includes(age)) {
        await interaction.reply({
          components: [
            errorContainer(
              "**Sorry**, you must be 15 or older to join CrabCraft.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
        if (logChannel) {
          await logChannel.send({
            components: [logAutoReject(interaction.user.id, "Under 15")],
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
    if (interaction.customId.startsWith("edit-application:")) {
      const appMessageId = interaction.customId.split(":")[1];

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
          over15: ACCEPTED_VALUES.includes(age) || parseInt(age) >= 15,
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

      // Edit the original application message
      if (appMessageId && interaction.channel) {
        try {
          const appMessage = await interaction.channel.messages.fetch(appMessageId);
          if (appMessage) {
            const fields = [
              `**Are you 15 or older?**\n${age}`,
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

            const adminAcceptButton = new ButtonBuilder()
              .setCustomId(`app_accept:${minecraftUsername}`)
              .setLabel("Accept")
              .setStyle(ButtonStyle.Success);

            const adminRejectButton = new ButtonBuilder()
              .setCustomId(`app_deny:${minecraftUsername}`)
              .setLabel("Deny")
              .setStyle(ButtonStyle.Danger);

            const adminButtons = new ActionRowBuilder<ButtonBuilder>().addComponents(
              adminAcceptButton,
              adminRejectButton,
            );

            await appMessage.edit({
              components: [updatedContainer, adminButtons],
              flags: MessageFlags.IsComponentsV2,
            });
          }
        } catch (e) {
          logger.error("Failed to edit application message:", e);
        }
      }

      await interaction.reply({
        components: [primaryContainer("Your application has been updated.")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
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
        await ticketChannel.send({ content: `<@${interaction.user.id}> <@&${config.MOD_ROLE_ID}>` });
        await ticketChannel.send({
          components: [
            buildTicketHeader(ticket, meta, player, intake),
            buildStaffButtons(ticket.id),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Ticket: failed to send opening message:", e);
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
      const reason =
        interaction.fields.getTextInputValue("deny_reason") ||
        "No reason provided";

      const denyContainer = errorContainer(
        `## Application Denied\nYour application has been **denied**.\n**Reason**\n\`\`\`${reason}\`\`\`\n-# This thread will be deleted in 12 hours`,
      );

      const appThread = interaction.channel as ThreadChannel;
      const denyThreadRow = await appDb
        .getApplicationThreadByThreadId(interaction.channelId ?? "")
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

      const applicantId = denyThreadRow?.applicant_id;
      try {
        if (applicantId) {
          await appThread.send({ content: `<@${applicantId}>` });
        }
        await appThread.send({
          components: [denyContainer],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Failed to send denial message:", e);
      }

      await interaction.reply({
        content: "Successfully denied application.",
        flags: MessageFlags.Ephemeral,
      });

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
        await saveTranscriptToLog(appThread, logChannel, `denied by ${interaction.user.tag}`).catch(() => null);
      }

      // Schedule deletion: the periodic + startup cleanup scans remove the
      // thread (and the applicant's channel access) once this passes.
      const denyDeleteAt = Math.floor((Date.now() + CHANNEL_DELETE_DELAY_MS) / 1000);
      if (denyThreadRow) {
        await appDb
          .setApplicationThreadDeleteAfter(denyThreadRow.thread_id, denyDeleteAt)
          .catch(() => null);
      } else {
        // No tracking row (anomaly) — fall back to a direct timed deletion so
        // the thread isn't left orphaned (the cleanup scan only sees rows).
        logger.warn(
          `Deny: no application_threads row for thread ${appThread.id}; scheduling direct deletion.`,
        );
        setTimeout(() => {
          appThread.delete("Application denied").catch(() => null);
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
      await interaction.reply({
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
      `**Are you 15 or older?**\n${age}`,
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

    const adminAcceptButton = new ButtonBuilder()
      .setCustomId(`app_accept:${minecraftUsername}`)
      .setLabel("Accept")
      .setStyle(ButtonStyle.Success);

    const adminRejectButton = new ButtonBuilder()
      .setCustomId(`app_deny:${minecraftUsername}`)
      .setLabel("Deny")
      .setStyle(ButtonStyle.Danger);

    const adminButtons = new ActionRowBuilder<ButtonBuilder>().addComponents(
      adminAcceptButton,
      adminRejectButton,
    );

    await interaction.reply({
      components: [submittedContainer, adminButtons],
      flags: MessageFlags.IsComponentsV2,
    });

    // Get the application message ID so the edit button can reference it
    const appMessage = await interaction.fetchReply().catch(() => null);
    const appMessageId = appMessage?.id ?? "";

    const agreeButton = new ButtonBuilder()
      .setCustomId("agree")
      .setEmoji("✅")
      .setLabel("I Agree")
      .setStyle(ButtonStyle.Success);

    const disagreeButton = new ButtonBuilder()
      .setCustomId("disagree")
      .setEmoji("❎")
      .setLabel("I Disagree")
      .setStyle(ButtonStyle.Danger);

    const editButton = new ButtonBuilder()
      .setCustomId(`edit_app:${appMessageId}`)
      .setLabel("Edit Application")
      .setStyle(ButtonStyle.Secondary)
      .setEmoji("✏️");

    const actionRow = new ActionRowBuilder<ButtonBuilder>().addComponents(
      agreeButton,
      disagreeButton,
      editButton,
    );

    // Persist to database. The MC link in `players` is claimed at accept
    // time only — submitting an application shouldn't yank another
    // player's MC link out of the players table.
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
        over15: ACCEPTED_VALUES.includes(age) || parseInt(age) >= 15,
        voiceChat: ACCEPTED_VALUES.includes(ingameVoice),
        joinReason: joinReason,
        favouriteWood: favouriteWood || undefined,
      });
      persisted = true;
    } catch (e) {
      logger.error("Failed to persist application to database:", e);
    }

    // If we couldn't save the application, don't show the policy — the
    // "I Agree" button would have no row to flip and staff would later
    // see "applicant hasn't agreed to the policy yet" with no recourse.
    if (!persisted) {
      try {
        await interaction.followUp({
          components: [
            errorContainer(
              "**Error!** Failed to save your application. Please contact a moderator.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Failed to send persist-failure notice:", e);
      }
      return;
    }

    // Policy message
    const policyContainer = primaryContainer(
      "## CrabCraft's Griefing & Stealing Policy\n**Just before we review your application, we need to ensure that you understand our policy on stealing.**\n\nWe have a zero tolerance policy towards stealing. If you steal from another player, you will be banned from the server.\nAll block interactions are logged, and all players are able to look into chest logs. This means any stealing will be traced back to the offending player.",
    );

    try {
      await interaction.followUp({
        components: [policyContainer, actionRow],
        flags: MessageFlags.IsComponentsV2,
      });
    } catch (e) {
      logger.error("Failed to send policy message:", e);
    }
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
        over15: true,
        voiceChat: true,
      });
      await appDb.acceptApplication(interaction.user.id, interaction.user.id);
    } catch (e) {
      logger.error("Fast application: failed to persist to database:", e);
    }
  }
}
