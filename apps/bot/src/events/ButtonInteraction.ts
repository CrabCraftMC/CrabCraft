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
import { errorContainer, successContainer, primaryContainer, coloredContainer, logAccept } from "../utils/embeds.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import { resolveUsername } from "../utils/mojang.js";
import { CHANNEL_DELETE_DELAY_MS } from "../utils/constants.js";
import { saveTranscriptToLog } from "../utils/transcript.js";

export default class ButtonInteractionEvent extends Event {
  constructor() {
    super("ButtonInteraction", "interactionCreate", false);
  }

  async execute(interaction: ButtonInteraction) {
    if (!interaction.isButton()) return;

    // I have no idea if bots can click buttons or not.. but this is here incase
    if (interaction.user.bot) return;

    if (interaction.customId == "apply") {
      // Only the channel's applicant can use the apply button
      const appChannel = interaction.channel as TextChannel;
      if (!appChannel.topic || appChannel.topic.split("|")[0] !== interaction.user.id) {
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

      const applicationModal = new ModalBuilder()
        .setCustomId("application")
        .setTitle("Season 6 Application");

      const minecraftUsername = new TextInputBuilder()
        .setCustomId("minecraft-username")
        .setLabel("Minecraft Username")
        .setPlaceholder("Steve")
        .setRequired(true)
        .setStyle(TextInputStyle.Short);

      const age = new TextInputBuilder()
        .setCustomId("age")
        .setLabel("Are you 15 or older?")
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

      const applicationModal = new ModalBuilder()
        .setCustomId("fast-application")
        .setTitle("Season 6 Application");

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
      const agreeChannel = interaction.channel as TextChannel;
      if (!agreeChannel.topic || agreeChannel.topic.split("|")[0] !== interaction.user.id) {
        await interaction.reply({
          components: [errorContainer("This button is not for you.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.message.edit({
        components: [
          coloredContainer(
            "## Success\nYou have **agreed** to **CrabCraft's Griefing & Stealing Policy**",
            "Green",
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });

      await appDb.setPolicyAgreed(interaction.user.id, true);

      await interaction.reply({
        content: "Policy agreed",
        flags: MessageFlags.Ephemeral,
      });
    }

    if (interaction.customId == "disagree") {
      const disagreeChannel = interaction.channel as TextChannel;
      if (!disagreeChannel.topic || disagreeChannel.topic.split("|")[0] !== interaction.user.id) {
        await interaction.reply({
          components: [errorContainer("This button is not for you.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.message.edit({
        components: [
          coloredContainer(
            "## Policy Rejected\nYou have **disagreed** to **CrabCraft's Griefing & Stealing Policy**",
            "Red",
          ),
        ],
        flags: MessageFlags.IsComponentsV2,
      });

      await appDb.setPolicyAgreed(interaction.user.id, false);

      await interaction.reply({
        content: "Policy disagreed",
        flags: MessageFlags.Ephemeral,
      });
    }

    // On app_accept — supports both new V2 (app_accept:username) and legacy (app_accept) formats
    if (interaction.customId === "app_accept" || interaction.customId.startsWith("app_accept:")) {
      if (
        !(interaction.member as GuildMember)?.roles.cache.has(
          config.MOD_ROLE_ID,
        )
      ) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions**")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

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
        await interaction.reply({
          components: [
            errorContainer(
              "**Error!** Could not find the Minecraft username from the application.",
            ),
          ],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      const appChannel = interaction.message.channel as TextChannel;
      const applicantId = appChannel.topic?.split("|")[0];

      if (!applicantId) {
        await interaction.reply({
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
        await interaction.reply({
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
        await interaction.reply({
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
        await interaction.reply({
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

      try {
        await mysql.query(
          "INSERT INTO discordsrv_accounts (uuid, discord) VALUES (?, ?)",
          [UUID, applicantId],
        );
      } catch (error) {
        logger.error("Failed to insert whitelist record:", error);
        await interaction.reply({
          components: [
            errorContainer(
              "**Error!** Failed to add the user to the whitelist database.",
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

      try { await appDb.acceptApplication(applicant.id, interaction.user.id); }
      catch (e) { logger.error("Failed to update application accept status:", e); }

      try {
        // @ts-ignore
        await interaction.channel.send({ content: `<@${applicant.id}>` });
        // @ts-ignore
        await interaction.channel.send({
          components: [
            successContainer(
              `## Application Accepted\n**Congratulations ${applicant.user.username}, your application has been accepted!**\n### Next Steps\nCheck out [our guide](https://wiki.crabcraft.net/Setup_Guide) for help installing **Simple Voice Chat**\n\nNeed anymore help? Let us know in this channel, or create a ticket <#1397191941782896670>\n-# Channel will be deleted in 12 hours`,
            ),
          ],
          flags: MessageFlags.IsComponentsV2,
        });
      } catch (e) {
        logger.error("Failed to send acceptance message:", e);
      }

      if (logChannel) {
        await saveTranscriptToLog(appChannel, logChannel, `accepted by ${interaction.user.tag}`).catch(() => null);
      }

      await appChannel.setTopic(`${appChannel.topic}|delete-after:${Date.now() + CHANNEL_DELETE_DELAY_MS}`).catch(() => null);
      setTimeout(
        async () => {
          await appChannel.delete().catch(() => null);
        },
        CHANNEL_DELETE_DELAY_MS,
      );

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

      await interaction.reply({
        components: [primaryContainer("Successfully accepted application")],
        flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
      });
    }

    // On app_deny — supports both V2 and legacy formats
    if (interaction.customId === "app_deny" || interaction.customId.startsWith("app_deny:")) {
      if (
        !(interaction.member as GuildMember)?.roles.cache.has(
          config.MOD_ROLE_ID,
        )
      ) {
        await interaction.reply({
          components: [errorContainer("**Missing permissions**")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      // Pass MC username through the modal customId for V2
      const minecraftUsername = interaction.customId.includes(":")
        ? interaction.customId.split(":")[1]
        : "";

      const denyModal = new ModalBuilder()
        .setCustomId(minecraftUsername ? `deny_modal:${minecraftUsername}` : "deny_modal")
        .setTitle("Deny Application");

      const reasonInput = new TextInputBuilder()
        .setCustomId("deny_reason")
        .setLabel("Reason (Optional)")
        .setStyle(TextInputStyle.Paragraph)
        .setRequired(false);

      const actionRow = new ActionRowBuilder<TextInputBuilder>().addComponents(
        reasonInput,
      );

      denyModal.addComponents(actionRow);

      await interaction.showModal(denyModal);
    }

    // Edit application button
    if (interaction.customId.startsWith("edit_app:")) {
      const appChannel = interaction.channel as TextChannel;
      if (!appChannel.topic || appChannel.topic.split("|")[0] !== interaction.user.id) {
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

      const appMessageId = interaction.customId.split(":")[1];

      const editModal = new ModalBuilder()
        .setCustomId(`edit-application:${appMessageId}`)
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
        .setLabel("Are you 15 or older?")
        .setPlaceholder("Answer must be: Y/N")
        .setRequired(true)
        .setValue(application.over_15 ? "Yes" : "No")
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
