import Event from "../structures/Event.js";
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonInteraction,
  ComponentType,
  GuildMember,
  MessageFlags,
  ModalBuilder,
  TextChannel,
  TextInputBuilder,
  TextInputStyle,
  type ThreadChannel,
} from "discord.js";
import { errorContainer, successContainer, primaryContainer, coloredContainer, logAccept } from "../utils/embeds.js";
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
  buildClosedNotice,
  buildClosedTicketButtons,
  buildDisabledClosedTicketButtons,
  buildDisabledStaffButtons,
  buildInfractionEmbedMessage,
  buildIntakeModal,
  buildStaffButtons,
  buildTicketLimitNotice,
  getCategoryMeta,
  getPlayerInfo,
  TICKET_INFRACTION_BUTTON_PREFIX,
} from "../utils/ticket.js";
import { openTicket } from "../utils/ticketFlow.js";
import {
  buildDisabledShopDeedStaffButtons,
  buildShopDeedDecisionModal,
  buildShopDeedHeader,
  buildShopDeedModal,
  buildShopDeedThreadName,
} from "../utils/shopDeed.js";
import { buildDenyModal } from "../utils/denyReasons.js";

function intakeString(intake: unknown, key: string): string | null {
  if (typeof intake !== "object" || intake === null) return null;
  const value = (intake as Record<string, unknown>)[key];
  return typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : null;
}

/**
 * Re-enable the Close button on a ticket's main (pinned) message. Used when a
 * ticket is reopened — the closed notice carries the Reopen/Delete buttons, but
 * the Close button lives on the original pinned header message.
 */
async function reEnableCloseButton(
  channel: TextChannel,
  ticketId: number,
): Promise<void> {
  try {
    const pins = await channel.messages.fetchPinned();
    const header = pins.find((message) =>
      message.components.some(
        (row) =>
          row.type === ComponentType.ActionRow &&
          row.components.some(
            (child) =>
              "customId" in child &&
              child.customId === `ticket_close:${ticketId}`,
          ),
      ),
    );
    if (!header) {
      logger.warn(
        `Ticket: could not find pinned header to re-enable close for #${ticketId}`,
      );
      return;
    }
    const otherComponents = header.components.filter(
      (row) => row.type !== ComponentType.ActionRow,
    );
    await header.edit({
      components: [...otherComponents, buildStaffButtons(ticketId)],
      flags: MessageFlags.IsComponentsV2,
    });
  } catch (e) {
    logger.error("Ticket: failed to re-enable close button:", e);
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

      if (member.roles.cache.has(config.MEMBER_ROLE_ID)) {
        await interaction.reply({
          components: [
            successContainer(
              "## <:Crab:1397355651822256299> You're already in!\nYou're already a member of CrabCraft, so there's no need to apply again. Enjoy your stay!",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferReply({ flags: MessageFlags.Ephemeral });

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

    if (interaction.customId === "shop_deed_open") {
      const eligible = await appDb
        .isWhitelistedForCurrentSeason(interaction.user.id)
        .catch((e) => {
          logger.error("Shop deed: eligibility check failed:", e);
          return false;
        });
      if (!eligible) {
        await interaction.reply({
          components: [
            errorContainer(
              "Shop deed requests are only available to players accepted for the current season.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.showModal(buildShopDeedModal());
      return;
    }

    if (
      interaction.customId.startsWith("shop_deed_accept:") ||
      interaction.customId.startsWith("shop_deed_changes:") ||
      interaction.customId.startsWith("shop_deed_reject:")
    ) {
      const member = interaction.member as GuildMember | null;
      if (!member?.roles.cache.has(config.MOD_ROLE_ID)) {
        await interaction.reply({
          content: `You must have the <@&${config.MOD_ROLE_ID}> role to do this.`,
          flags: MessageFlags.Ephemeral,
        });
        return;
      }

      const [action, rawId] = interaction.customId.split(":");
      const applicationId = Number(rawId);
      if (!Number.isFinite(applicationId)) {
        await interaction.reply({
          components: [errorContainer("Shop deed request not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const application = await appDb.getShopDeedApplicationById(applicationId);
      if (!application || application.thread_id !== interaction.channelId) {
        await interaction.reply({
          components: [errorContainer("Shop deed request not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      if (
        application.status !== "pending" &&
        application.status !== "changes_requested"
      ) {
        await interaction.reply({
          components: [errorContainer("This shop deed request has already been processed.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      if (action === "shop_deed_accept") {
        await interaction.deferUpdate();

        const resolved = await appDb.resolveShopDeedApplication(
          application.id,
          "accepted",
          null,
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

        try {
          await interaction.message.edit({
            components: [
              buildShopDeedHeader(resolved),
              buildDisabledShopDeedStaffButtons(resolved.id),
            ],
            flags: MessageFlags.IsComponentsV2,
          });
        } catch (e) {
          logger.error("Shop deed: failed to update accepted request:", e);
        }

        try {
          await (interaction.channel as ThreadChannel | null)?.setName(
            buildShopDeedThreadName(
              resolved.shop_name,
              resolved.applicant_discord_username,
              resolved.status,
            ),
            "Shop deed accepted",
          );
        } catch (e) {
          logger.error("Shop deed: failed to rename accepted thread:", e);
        }
        return;
      }

      await interaction.showModal(
        buildShopDeedDecisionModal(
          application.id,
          action === "shop_deed_changes" ? "changes_requested" : "rejected",
        ),
      );
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

    if (interaction.customId == "fast-apply") {
      const hasPendingFast = await appDb.hasPendingApplication(interaction.user.id);
      if (hasPendingFast) {
        await interaction.reply({
          components: [errorContainer("You've already submitted an application. Please wait for it to be reviewed.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const currentSeasonFast = await appDb.getCurrentSeason().catch(() => null);
      const applicationModal = new ModalBuilder()
        .setCustomId("fast-application")
        .setTitle(`${currentSeasonFast?.name ?? "Server"} Application`.slice(0, 45));

      const minecraftUsername = new TextInputBuilder()
        .setCustomId("minecraft-username")
        .setLabel("Minecraft Username")
        .setPlaceholder("Steve")
        .setRequired(true)
        .setStyle(TextInputStyle.Short);

      const firstActionRow =
        new ActionRowBuilder<TextInputBuilder>().addComponents(
          minecraftUsername,
        );

      applicationModal.addComponents(firstActionRow);

      await interaction.showModal(applicationModal);
    }

    if (interaction.customId === "retry-username" || interaction.customId === "retry-fast-username") {
      const modalId = interaction.customId === "retry-username" ? "retry-application" : "retry-fast-application";
      const retryModal = new ModalBuilder()
        .setCustomId(modalId)
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
          components: [logAccept(applicant.id, minecraftUsername, UUID, `${interaction.member}`)],
          flags: MessageFlags.IsComponentsV2,
        }).catch((e: unknown) => logger.error("Failed to send accept log:", e));
      }

      try { await applicant.roles.add(config.MEMBER_ROLE_ID); }
      catch (e) { logger.error("Failed to add member role:", e); }

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

      // (application status was already flipped above as the race guard)

      try {
        await appChannel.send({
          content: applicationAcceptedMessage(applicant.id),
        });
      } catch (e) {
        logger.error("Failed to send acceptance message:", e);
      }

      if (logChannel) {
        await saveTranscriptToLog(appChannel, logChannel, `accepted by ${interaction.user.tag}`).catch(() => null);
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

      try {
        const openTickets = await appDb.listOpenTicketsForUser(
          interaction.user.id,
        );
        const sameCategory = openTickets.filter(
          (t) => t.category === meta.category,
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

      // Categories with no intake questions (e.g. General Question) skip the
      // modal entirely and open a ticket straight away.
      if (meta.fields.length === 0 && !meta.fileField) {
        await interaction.deferReply({
          flags: MessageFlags.Ephemeral | MessageFlags.IsComponentsV2,
        });
        const player = await getPlayerInfo(
          interaction.user.id,
          interaction.user.tag,
        );
        await openTicket({ interaction, meta, player, intake: {} });
        return;
      }

      // Appeals normally pull the appellant's Minecraft account from our
      // database. If they aren't linked, ask for their username in the modal so
      // we can still look up their punishment history.
      let includeMinecraftUsername = false;
      if (meta.category === "appeal") {
        try {
          const player = await getPlayerInfo(
            interaction.user.id,
            interaction.user.tag,
          );
          includeMinecraftUsername = !player.minecraftUuid;
        } catch (e) {
          logger.error("Ticket: appeal link check failed:", e);
          includeMinecraftUsername = true;
        }
      }

      await interaction.showModal(
        buildIntakeModal(meta, { includeMinecraftUsername }),
      );
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

      const ticket = await appDb.getTicketById(ticketId);
      if (!ticket) {
        await interaction.reply({
          components: [errorContainer("Ticket not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }
      if (ticket.channel_id !== interaction.channelId) {
        await interaction.reply({
          components: [errorContainer("This button is not for this channel.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }
      if (ticket.status === "closed") {
        await interaction.reply({
          components: [errorContainer("This ticket is already closed.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const member = interaction.member as GuildMember | null;
      const isMod = member?.roles.cache.has(config.MOD_ROLE_ID) ?? false;
      const isOpener = ticket.opener_discord_id === interaction.user.id;
      if (!isMod && !isOpener) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions** — only the opener or staff can close.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const deleteAtSeconds = Math.floor((Date.now() + TICKET_DELETE_DELAY_MS) / 1000);

      try {
        await appDb.closeTicket(ticket.id, interaction.user.id, deleteAtSeconds);
      } catch (e) {
        logger.error("Ticket: close DB update failed:", e);
        await interaction.reply({
          components: [errorContainer("Failed to close. Please try again.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.deferReply({ flags: MessageFlags.Ephemeral });

      const ticketChannel = interaction.channel as TextChannel | null;

      // Disable the Close button on the staff row that was just clicked.
      try {
        const otherComponents = interaction.message.components.filter(
          (row) => row.type !== ComponentType.ActionRow,
        );
        await interaction.message.edit({
          components: [...otherComponents, buildDisabledStaffButtons(ticket.id)],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Ticket: failed to disable staff buttons:", e);
      }

      // Note: the transcript is intentionally NOT saved here. It's generated
      // only when the ticket is actually deleted (manual Delete button or the
      // 24-hour cleanup scan), so a reopened ticket isn't transcribed early.

      // Revoke the opener's per-channel ViewChannel grant so the channel
      // disappears from their sidebar. Mods retain access via MOD_ROLE_ID.
      if (ticketChannel) {
        try {
          await ticketChannel.permissionOverwrites.delete(
            ticket.opener_discord_id,
            "Ticket closed",
          );
        } catch (e) {
          logger.error("Ticket: failed to revoke opener access:", e);
        }
      }

      // Post the closed panel + Reopen / Delete buttons at the bottom.
      if (ticketChannel) {
        try {
          await ticketChannel.send({
            components: [
              buildClosedNotice(`<@${interaction.user.id}>`, deleteAtSeconds),
              buildClosedTicketButtons(ticket.id),
            ],
            flags: MessageFlags.IsComponentsV2,
          });
        } catch (e) {
          logger.error("Ticket: failed to send closed notice:", e);
        }
      }

      await interaction.editReply({
        components: [primaryContainer("Ticket closed.")],
      });
      return;
    }

    // Reopen: mods only. Restores opener access, cancels the delete schedule.
    if (interaction.customId.startsWith("ticket_reopen:")) {
      const ticketId = Number(interaction.customId.split(":")[1]);
      if (!Number.isFinite(ticketId)) return;

      const member = interaction.member as GuildMember | null;
      if (!member?.roles.cache.has(config.MOD_ROLE_ID)) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions** — only staff can reopen tickets.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const ticket = await appDb.getTicketById(ticketId);
      if (!ticket) {
        await interaction.reply({
          components: [errorContainer("Ticket not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }
      if (ticket.status !== "closed") {
        await interaction.reply({
          components: [errorContainer("This ticket isn't closed.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      try {
        await appDb.reopenTicket(ticket.id);
      } catch (e) {
        logger.error("Ticket: reopen failed:", e);
        await interaction.reply({
          components: [errorContainer("Failed to reopen. Please try again.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      // Acknowledge the button without posting a follow-up message.
      await interaction.deferUpdate();

      const ticketChannel = interaction.channel as TextChannel | null;

      // Restore the opener's per-channel ViewChannel grant.
      if (ticketChannel) {
        try {
          await ticketChannel.permissionOverwrites.create(ticket.opener_discord_id, {
            ViewChannel: true,
            SendMessages: true,
            ReadMessageHistory: true,
          });
        } catch (e) {
          logger.error("Ticket: failed to restore opener access:", e);
        }
      }

      // Grey out the Reopen / Delete buttons on the closed notice just clicked.
      try {
        const otherComponents = interaction.message.components.filter(
          (row) => row.type !== ComponentType.ActionRow,
        );
        await interaction.message.edit({
          components: [...otherComponents, buildDisabledClosedTicketButtons(ticket.id)],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Ticket: failed to disable closed-notice buttons:", e);
      }

      // Re-enable the Close button on the main (pinned) ticket message.
      if (ticketChannel) {
        await reEnableCloseButton(ticketChannel, ticket.id);
      }
      return;
    }

    // Delete: mods only. Saves the transcript, then removes the channel + row.
    if (interaction.customId.startsWith("ticket_delete:")) {
      const ticketId = Number(interaction.customId.split(":")[1]);
      if (!Number.isFinite(ticketId)) return;

      const member = interaction.member as GuildMember | null;
      if (!member?.roles.cache.has(config.MOD_ROLE_ID)) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions** — only staff can delete tickets.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const ticket = await appDb.getTicketById(ticketId);
      if (!ticket) {
        await interaction.reply({
          components: [errorContainer("Ticket not found.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }
      if (ticket.channel_id !== interaction.channelId) {
        await interaction.reply({
          components: [errorContainer("This button is not for this channel.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      // Transcript generation reads the whole channel, so ack first.
      await interaction.deferUpdate();

      const ticketChannel = interaction.channel as TextChannel | null;

      // Save the transcript to the log channel before deletion.
      try {
        const logChannel = await interaction.guild?.channels
          .fetch(config.LOG_CHANNEL_ID)
          .catch(() => null) as TextChannel | null;
        if (logChannel && ticketChannel) {
          await saveTranscriptToLog(
            ticketChannel,
            logChannel,
            `ticket #${ticket.id} deleted by ${interaction.user.tag}`,
          ).catch(() => null);
        }
      } catch (e) {
        logger.error("Ticket: transcript failed:", e);
      }

      try {
        await appDb.deleteTicketRow(ticket.id);
      } catch (e) {
        logger.error("Ticket: failed to delete row:", e);
      }

      await ticketChannel
        ?.delete(`Ticket #${ticket.id} deleted by ${interaction.user.tag}`)
        .catch((e) => logger.error("Ticket: failed to delete channel:", e));
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
