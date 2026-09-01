import Event from "../structures/Event.js";
import config from "../utils/config.js";
import {
  errorContainer,
  primaryContainer,
  logAutoReject,
  logDeny,
} from "../utils/embeds.js";
import logger from "../utils/logger.js";
import {
  type GuildMember,
  type ModalSubmitInteraction,
  type TextChannel,
  ButtonBuilder,
  ButtonStyle,
  ActionRowBuilder,
  ComponentType,
  ContainerBuilder,
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
  applicationDeniedMessage,
} from "../utils/applicationMessages.js";
import {
  fetchPlayerInfractions,
  type TicketInfractionInfo,
} from "../utils/infractions.js";
import {
  buildTicketLimitNotice,
  getCategoryMeta,
  getPlayerInfo,
  type EvidenceFile,
} from "../utils/ticket.js";
import {
  getLiveOpenTicketsForCategory,
  openTicket,
} from "../utils/ticketFlow.js";
import {
  DENY_REASON_CUSTOM_ID,
  DENY_REASON_PRESET_SELECT_ID,
  resolveDenyReason,
} from "../utils/denyReasons.js";
import { AnalyticsEvent } from "@crabcraft/shared/analytics";
import { captureMinecraftEvent } from "../utils/analytics.js";

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
            content: logAutoReject(
              interaction.user.id,
              "Age requirement not met (must be 17+)",
            ),
            allowedMentions: { parse: [] },
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
        logger.error("Ticket: rate-limit re-check failed:", e);
      }

      // Defer with the Components V2 flag so the follow-up editReply containers
      // render. The flag is fixed at message creation and cannot be added later.
      await interaction.deferReply({
        flags: MessageFlags.Ephemeral | MessageFlags.IsComponentsV2,
      });

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
          flags: MessageFlags.IsComponentsV2,
        });
        return;
      }

      // Collect any uploaded evidence files (griefing reports).
      const evidenceFiles: EvidenceFile[] = [];
      if (meta.fileField) {
        try {
          const files = interaction.fields.getUploadedFiles(meta.fileField.id);
          if (files) {
            for (const file of files.values()) {
              evidenceFiles.push({
                url: file.url,
                name: file.name ?? "file",
                contentType: file.contentType ?? null,
              });
            }
          }
        } catch {
          // No files uploaded — fine, the field is optional.
        }
      }

      // Pull player context up-front: it provides the linked Minecraft account
      // used for the appeal punishment lookup, and is reused for the ticket row
      // and header below.
      const player = await getPlayerInfo(
        interaction.user.id,
        interaction.user.tag,
      );

      // Appeals: only load punishment history for the Discord account's
      // verified Minecraft link. A typed username is not proof of ownership.
      let ticketInfractionInfo: TicketInfractionInfo | null = null;
      if (meta.category === "appeal") {
        let uuid = player.minecraftUuid;
        let name = player.minecraftUsername;

        if (uuid) {
          const lookupName = name ?? interaction.user.username;
          intake.resolved_minecraft_username = lookupName;
          intake.resolved_minecraft_uuid = uuid;
          ticketInfractionInfo = await fetchPlayerInfractions(
            lookupName,
            uuid,
            25,
          );
        } else {
          ticketInfractionInfo = {
            username: name ?? interaction.user.username,
            uuid: null,
            infractions: null,
            error: "no verified Minecraft account is linked to this Discord user",
          };
        }
      }

      await openTicket({
        interaction,
        meta,
        player,
        intake,
        evidenceFiles,
        ticketInfractionInfo,
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

      // The deny modal leads with a preset-reason dropdown and a custom-reason
      // text box. Either may be absent (both are optional), so read each
      // defensively and let resolveDenyReason combine them.
      let presetValue: string | undefined;
      try {
        presetValue = interaction.fields.getStringSelectValues(
          DENY_REASON_PRESET_SELECT_ID,
        )[0];
      } catch {
        presetValue = undefined;
      }
      let customReason = "";
      try {
        customReason = interaction.fields.getTextInputValue(
          DENY_REASON_CUSTOM_ID,
        );
      } catch {
        customReason = "";
      }
      const reason = resolveDenyReason(presetValue, customReason);

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
          content: applicationDeniedMessage(reason),
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
          content: logDeny(applicantId ?? "unknown", minecraftUsername, reason, `${interaction.user}`),
          allowedMentions: { parse: [] },
        }).catch(() => null);
      }

      // Update application status in database
      if (applicantId) {
        try {
          const application = await appDb.getLatestApplication(applicantId);
          const denied = await appDb.denyApplication(
            applicantId,
            reason,
            interaction.user.id,
          );
          if (denied && application?.minecraft_uuid) {
            captureMinecraftEvent(
              application.minecraft_uuid,
              AnalyticsEvent.APPLICATION_RESOLVED,
              {
                outcome: "denied",
                review_duration_seconds: Math.max(
                  0,
                  Math.floor(Date.now() / 1_000) - application.applied_at,
                ),
                season: application.season,
              },
              {
                dedupeKey: `${application.id}:${application.applied_at}:denied`,
              },
            );
          }
        } catch (e) {
          logger.error("Failed to update deny status in database:", e);
        }
      }

      if (logChannel) {
        await saveTranscriptToLog(appChannel, logChannel);
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
          content: logAutoReject(interaction.user.id, "Already a member"),
          allowedMentions: { parse: [] },
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
    let applicationRecord: { id: number; appliedAt: number } | null = null;
    try {
      await appDb.upsertUser({
        discordId: interaction.user.id,
        discordUsername: interaction.user.username,
      });
      applicationRecord = await appDb.createApplication({
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

    if (!persisted || !applicationRecord) {
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

    captureMinecraftEvent(
      UUID,
      AnalyticsEvent.APPLICATION_SUBMITTED,
      {
        season: currentSeason?.id ?? null,
        voice_chat_opt_in: ACCEPTED_VALUES.includes(ingameVoice),
      },
      {
        dedupeKey: `${applicationRecord.id}:${applicationRecord.appliedAt}`,
      },
    );

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
}
