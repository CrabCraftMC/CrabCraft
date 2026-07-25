import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ChannelType,
  MessageFlags,
  PermissionFlagsBits,
  TextDisplayBuilder,
  type CategoryChannel,
  type Guild,
  type GuildMember,
  type TextChannel,
} from "discord.js";
import config from "./config.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";
import type { ApplicationChannel } from "./appDb.js";
import { primaryContainer } from "./embeds.js";
import { saveTranscriptToLog } from "./transcript.js";

// Each applicant gets a dedicated private text channel under the configured
// application category. Channel↔applicant identity and lifecycle state live
// in the `application_channels` table (the source of truth), rather than being
// packed into the channel `topic` string.

/** Resolve the configured application category. */
export async function getApplicationCategory(
  guild: Guild,
): Promise<CategoryChannel | null> {
  const channel = await guild.channels
    .fetch(config.APPLICATION_CATEGORY_ID)
    .catch(() => null);
  if (!channel || channel.type !== ChannelType.GuildCategory) return null;
  return channel as CategoryChannel;
}

/** Channel name for an applicant (sanitised username, no prefix). */
export function buildApplicationChannelName(username: string): string {
  return (
    username
      .toLowerCase()
      .replace(/[^a-z0-9_-]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 90) || "applicant"
  );
}

/** The Apply button row shown on the welcome + reminder messages. */
export function buildApplyButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId("apply")
      .setLabel("Apply")
      .setStyle(ButtonStyle.Primary)
      .setEmoji("📝"),
  );
}

/** The Application Hub panel (posted via /admin send). */
export function buildApplicationHubContainer() {
  return primaryContainer(
    `## <:Crab:1397355651822256299> Welcome to CrabCraft\nIf you can see this message, it's because you are not yet whitelisted on our server.\n\nYou can get whitelisted by clicking the button below this message and filling out a short form. One of our moderators will get back to you as soon as possible.`,
  );
}

/** The "Open Application" button shown on the Application Hub panel. */
export function buildApplicationHubButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId("app_hub_open")
      .setLabel("Open Application")
      .setStyle(ButtonStyle.Primary)
      .setEmoji("📝"),
  );
}

/**
 * Post the welcome message: the applicant ping (as a text component) and the
 * welcome/requirements panel + Apply button, all in a single message.
 */
async function sendApplicationWelcome(
  channel: TextChannel,
  member: GuildMember,
): Promise<void> {
  try {
    await channel.send({
      components: [
        new TextDisplayBuilder().setContent(`<@!${member.id}>`),
        primaryContainer(
          `## <:Crab:1397355651822256299> Welcome to CrabCraft ${member.displayName}!\nClick the button below this message to start your application.\n\n**Requirements**\n- 17 or older\n- A Minecraft: Java Edition account\n\n-# Any problems? Just send a message in this channel.`,
        ),
        buildApplyButton(),
      ],
      flags: MessageFlags.IsComponentsV2,
    });
  } catch (e) {
    logger.error("Application: failed to send welcome message:", e);
  }
}

/**
 * Create (or reuse) the applicant's private application channel, record it,
 * and post the welcome message. Returns the channel, or null if the category
 * is misconfigured / creation failed.
 */
export async function createApplicationChannelFor(
  member: GuildMember,
): Promise<TextChannel | null> {
  const category = await getApplicationCategory(member.guild);
  if (!category) {
    logger.error(
      "Application: configured application category not found or is not a category.",
    );
    return null;
  }

  // Reuse an existing channel for this applicant if one is still around.
  let channel: TextChannel | null = null;
  const existing = await appDb
    .getApplicationChannelByApplicant(member.id)
    .catch(() => null);
  if (existing) {
    channel = (await member.guild.channels
      .fetch(existing.channel_id)
      .catch(() => null)) as TextChannel | null;
    if (!channel) {
      await appDb
        .deleteApplicationChannelRow(existing.channel_id)
        .catch(() => null);
    }
  }
  let createdChannel = false;
  try {
    if (!channel) {
      channel = await member.guild.channels.create({
        name: buildApplicationChannelName(member.user.username),
        type: ChannelType.GuildText,
        parent: category.id,
        permissionOverwrites: [
          {
            id: member.user.id,
            allow: [PermissionFlagsBits.ViewChannel],
          },
          {
            id: member.guild.roles.everyone,
            deny: [PermissionFlagsBits.ViewChannel],
          },
          {
            id: config.MOD_ROLE_ID,
            allow: [PermissionFlagsBits.ViewChannel],
          },
        ],
      });
      createdChannel = true;
    } else {
      await channel.permissionOverwrites.create(member.user.id, {
        ViewChannel: true,
      });
    }
  } catch (e) {
    logger.error(
      "Application: failed to create/configure application channel:",
      e,
    );
    return null;
  }

  try {
    await appDb.createApplicationChannel({
      channelId: channel.id,
      applicantId: member.id,
      applicantUsername: member.user.username,
      guildId: member.guild.id,
    });
  } catch (e) {
    logger.error("Application: failed to persist application channel:", e);
    if (createdChannel) {
      await channel
        .delete("Application record could not be persisted")
        .catch((deleteError) =>
          logger.error("Application: failed to roll back orphan channel:", deleteError),
        );
    }
    return null;
  }

  await sendApplicationWelcome(channel, member);
  return channel;
}

/**
 * One-time migration: adopt any pre-existing topic-based application channels
 * into the `application_channels` table so in-flight applications keep working
 * after the switch away from topic-stored state. Idempotent — channels already
 * tracked are skipped. The legacy topic format was
 * `<applicantId>[|delete-after:<ms>][|reminded]`.
 */
export async function backfillApplicationChannels(guild: Guild): Promise<void> {
  const category = await getApplicationCategory(guild);
  if (!category) return;

  for (const [, ch] of category.children.cache) {
    if (ch.type !== ChannelType.GuildText) continue;
    const channel = ch as TextChannel;
    if (!channel.name.startsWith("app-")) continue;

    const existing = await appDb
      .getApplicationChannelByChannelId(channel.id)
      .catch(() => null);
    if (existing) continue;

    const parts = (channel.topic ?? "").split("|");
    const applicantId = parts[0];
    // Only adopt channels whose topic begins with a Discord snowflake.
    if (!applicantId || !/^\d{17,20}$/.test(applicantId)) continue;

    try {
      await appDb.createApplicationChannel({
        channelId: channel.id,
        applicantId,
        applicantUsername: channel.name.replace(/^app-/, ""),
        guildId: guild.id,
      });
      // Preserve any scheduled deletion / reminder state from the old topic.
      if (parts.includes("reminded")) {
        await appDb.markApplicationChannelReminded(channel.id).catch(() => null);
      }
      const deleteAfter = parts.find((p) => p.startsWith("delete-after:"));
      if (deleteAfter) {
        const ms = Number(deleteAfter.split(":")[1]);
        if (Number.isFinite(ms)) {
          await appDb
            .setApplicationChannelDeleteAfter(channel.id, Math.floor(ms / 1000))
            .catch(() => null);
        }
      }
    } catch (e) {
      logger.error("Application: failed to backfill channel record:", e);
    }
  }
}

/**
 * Tear down an application channel: optionally save a transcript, delete the
 * channel, and drop the DB row. Never throws.
 */
export async function finalizeApplicationChannel(
  guild: Guild,
  row: ApplicationChannel,
  saveTranscript: boolean,
): Promise<void> {
  const channel = (await guild.channels
    .fetch(row.channel_id)
    .catch(() => null)) as TextChannel | null;

  if (channel && saveTranscript) {
    const logChannel = (await guild.channels
      .fetch(config.LOG_CHANNEL_ID)
      .catch(() => null)) as TextChannel | null;
    if (logChannel) {
      await saveTranscriptToLog(channel, logChannel);
    }
  }

  if (channel) {
    await channel
      .delete("Application resolved")
      .catch((e) => logger.error("Application: failed to delete channel:", e));
  }

  await appDb.deleteApplicationChannelRow(row.channel_id).catch(() => null);
}
