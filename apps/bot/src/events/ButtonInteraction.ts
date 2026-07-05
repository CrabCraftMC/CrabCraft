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
  buildClosedNotice,
  buildClosedTicketButtons,
  buildDisabledClosedTicketButtons,
  buildInfractionEmbedMessage,
  buildReopenedNotice,
  getCategoryMeta,
  TICKET_INFRACTION_BUTTON_PREFIX,
} from "../utils/ticket.js";
import { beginTicketOpen } from "../utils/ticketFlow.js";
import { buildDenyModal } from "../utils/denyReasons.js";
import {
  SEASON_PLAY_BUTTON_ID,
  buildOpenTicketButton,
} from "../utils/seasonAccess.js";

function intakeString(intake: unknown, key: string): string | null {
  if (typeof intake !== "object" || intake === null) return null;
  const value = (intake as Record<string, unknown>)[key];
  return typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : null;
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
          content: logAccept(applicant.id, minecraftUsername, UUID, `${interaction.member}`),
        }).catch((e: unknown) => logger.error("Failed to send accept log:", e));
      }

      try { await applicant.roles.add(config.MEMBER_ROLE_ID); }
      catch (e) { logger.error("Failed to add member role:", e); }

      // New members are joining for the current season, so grant its role
      // too (the same one the season access button hands out).
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

      // Anyone with access to this (private) ticket channel may close it.
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

      // Acknowledge the button without editing the header (its evidence File
      // components can't survive a re-send). The closed notice is posted as its
      // own message below.
      await interaction.deferUpdate();

      const ticketChannel = interaction.channel as TextChannel | null;

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

      // Post the closed notice + Reopen / Delete buttons as a new message.
      if (ticketChannel) {
        await ticketChannel
          .send({
            components: [
              buildClosedNotice(`<@${interaction.user.id}>`, deleteAtSeconds),
              buildClosedTicketButtons(ticket.id),
            ],
            // Render the closer's name without pinging them.
            allowedMentions: { parse: [] },
            flags: MessageFlags.IsComponentsV2,
          })
          .catch((e) => logger.error("Ticket: failed to send closed notice:", e));
      }
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
      // (The header's Close button stays active, so the ticket can be reclosed.)
      try {
        const otherComponents = interaction.message.components.filter(
          (row) => row.type !== ComponentType.ActionRow,
        );
        await interaction.message.edit({
          components: [
            ...otherComponents,
            buildDisabledClosedTicketButtons(ticket.id),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Ticket: failed to disable closed-notice buttons:", e);
      }

      // Announce the reopen (without pinging the reopener).
      if (ticketChannel) {
        await ticketChannel
          .send({
            components: [buildReopenedNotice(`<@${interaction.user.id}>`)],
            allowedMentions: { parse: [] },
            flags: MessageFlags.IsComponentsV2,
          })
          .catch((e) => logger.error("Ticket: failed to send reopened notice:", e));
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

      // Save the transcript to the ticket-log channel before deletion.
      try {
        const logChannel = await interaction.guild?.channels
          .fetch(config.TICKET_LOG_CHANNEL_ID)
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
