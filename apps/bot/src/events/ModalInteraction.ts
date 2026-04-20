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

      // Update Turso
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
          minecraftUsername: minecraftUsername,
          minecraftUuid: resolved.uuid,
        });
      } catch (e) {
        logger.error("Failed to update application in Turso:", e);
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

    // ── Deny Modal — supports V2 (deny_modal:username) and legacy (deny_modal) ──
    if (
      interaction.customId === "deny_modal" ||
      interaction.customId.startsWith("deny_modal:")
    ) {
      const reason =
        interaction.fields.getTextInputValue("deny_reason") ||
        "No reason provided";

      const denyContainer = errorContainer(
        `## Application Denied\nYour application has been **denied**.\n**Reason**\n\`\`\`${reason}\`\`\`\n-# Channel will be deleted in 12 hours`,
      );

      const appChannel = interaction.channel as TextChannel;

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

      const applicantId = appChannel.topic?.split("|")[0];
      try {
        if (applicantId) {
          await appChannel.send({ content: `<@${applicantId}>` });
        }
        await appChannel.send({
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

      // Update application status in Turso
      if (applicantId) {
        try {
          await appDb.denyApplication(applicantId, reason, interaction.user.id);
        } catch (e) {
          logger.error("Failed to update deny status in Turso:", e);
        }
      }

      if (logChannel) {
        await saveTranscriptToLog(appChannel, logChannel, `denied by ${interaction.user.tag}`).catch(() => null);
      }

      await appChannel.setTopic(`${applicantId}|delete-after:${Date.now() + CHANNEL_DELETE_DELAY_MS}`).catch(() => null);
      setTimeout(
        async () => {
          await appChannel.delete().catch(() => null);
        },
        CHANNEL_DELETE_DELAY_MS,
      );
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

    // If user is inactive (left previously), clean up stale whitelist record
    let isActive = true; // safe default: skip cleanup rather than risk deleting valid records
    try {
      isActive = await appDb.isUserActive(interaction.user.id);
    } catch (e) {
      logger.error("Failed to check user active status:", e);
    }
    if (!isActive) {
      try {
        await mysql.query("DELETE FROM discordsrv_accounts WHERE discord = ?", [
          interaction.user.id,
        ]);
      } catch (e) {
        logger.error("Failed to clean up stale whitelist record:", e);
      }
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

    // Persist to Turso
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
        over15: ACCEPTED_VALUES.includes(age) || parseInt(age) >= 15,
        voiceChat: ACCEPTED_VALUES.includes(ingameVoice),
        joinReason: joinReason,
        favouriteWood: favouriteWood || undefined,
      });
    } catch (e) {
      logger.error("Failed to persist application to Turso:", e);
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

    // If user is inactive (left previously), clean up stale whitelist record
    let isActive = true;
    try {
      isActive = await appDb.isUserActive(interaction.user.id);
    } catch (e) {
      logger.error("Failed to check user active status:", e);
    }
    if (!isActive) {
      try {
        await mysql.query("DELETE FROM discordsrv_accounts WHERE discord = ?", [
          interaction.user.id,
        ]);
      } catch (e) {
        logger.error("Failed to clean up stale whitelist record:", e);
      }
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

    // Persist to Turso (upsert user + create accepted application)
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
      logger.error("Fast application: failed to persist to Turso:", e);
    }
  }
}
