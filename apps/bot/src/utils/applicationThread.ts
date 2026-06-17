import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ChannelType,
  MessageFlags,
  ThreadAutoArchiveDuration,
  type Guild,
  type GuildMember,
  type TextChannel,
  type ThreadChannel,
} from "discord.js";
import config from "./config.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";
import type { ApplicationThread } from "./appDb.js";
import { primaryContainer } from "./embeds.js";
import { saveTranscriptToLog } from "./transcript.js";

// Applications now live as private threads under a single configured
// channel (config.APPLICATION_CHANNEL_ID) instead of one text channel per
// applicant under a category. Each applicant gets their own private thread
// that only they and the moderator role can see. Thread↔applicant identity
// and lifecycle state live in the `application_threads` table (threads have
// no `topic` to stash it in).

/** Resolve the configured application parent channel as a TextChannel. */
export async function getApplicationChannel(
  guild: Guild,
): Promise<TextChannel | null> {
  const channel = await guild.channels
    .fetch(config.APPLICATION_CHANNEL_ID)
    .catch(() => null);
  if (!channel || channel.type !== ChannelType.GuildText) return null;
  return channel as TextChannel;
}

/** Thread name for an applicant, e.g. `app-steve`. */
export function buildAppThreadName(username: string): string {
  const safe =
    username
      .toLowerCase()
      .replace(/[^a-z0-9_-]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 90) || "user";
  return `app-${safe}`;
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

/**
 * Ensure the moderator role can see + manage the application channel and
 * every private thread inside it (ManageThreads grants visibility into all
 * private threads). Idempotent; safe to call on every startup.
 */
export async function ensureApplicationChannelPermissions(
  guild: Guild,
): Promise<void> {
  const channel = await getApplicationChannel(guild);
  if (!channel) return;
  try {
    await channel.permissionOverwrites.edit(config.MOD_ROLE_ID, {
      ViewChannel: true,
      ReadMessageHistory: true,
      SendMessages: true,
      SendMessagesInThreads: true,
      ManageThreads: true,
      ManageMessages: true,
    });
  } catch (e) {
    logger.error(
      "Application: failed to ensure moderator permissions on application channel:",
      e,
    );
  }
}

/**
 * Grant the applicant access to the parent channel. A private thread
 * inherits ViewChannel from its parent, so a freshly-joined (un-roled)
 * applicant needs an explicit overwrite to reach their thread.
 */
async function grantApplicantParentAccess(
  parent: TextChannel,
  member: GuildMember,
): Promise<void> {
  try {
    await parent.permissionOverwrites.create(member.id, {
      ViewChannel: true,
      ReadMessageHistory: true,
      SendMessagesInThreads: true,
    });
  } catch (e) {
    logger.error("Application: failed to grant applicant channel access:", e);
  }
}

/** Add (or re-add) the applicant to their private thread, unarchiving first. */
async function addApplicantToThread(
  thread: ThreadChannel,
  member: GuildMember,
): Promise<void> {
  try {
    if (thread.archived) await thread.setArchived(false).catch(() => null);
    await thread.members.add(member.id);
  } catch (e) {
    logger.error("Application: failed to add applicant to thread:", e);
  }
}

/** Ping the applicant and post the welcome message + Apply button. */
async function sendApplicationWelcome(
  thread: ThreadChannel,
  member: GuildMember,
): Promise<void> {
  try {
    await thread.send({ content: `<@${member.id}>` });
    await thread.send({
      components: [
        primaryContainer(
          `## <:Crab:1397355651822256299> Welcome to CrabCraft ${member.displayName}!\nPlease click the button below this message to start your application.\n-# Any problems? Send a message in this thread.`,
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
 * Open (or reuse) a private application thread for a member, grant them
 * access, add + ping them, and post the welcome message. Returns the thread,
 * or null if the application channel is misconfigured / creation failed.
 */
export async function openApplicationThread(
  member: GuildMember,
): Promise<ThreadChannel | null> {
  const parent = await getApplicationChannel(member.guild);
  if (!parent) {
    logger.error(
      "Application: configured application channel not found or is not a text channel.",
    );
    return null;
  }

  // Reuse an existing live thread if this member already has one that hasn't
  // been resolved yet (e.g. a rejoin while still mid-application). A thread
  // with delete_after set is already winding down, so a fresh one is opened.
  const existing = await appDb
    .getApplicationThreadByApplicant(member.id)
    .catch(() => null);
  if (existing && existing.delete_after === null) {
    const thread = (await member.guild.channels
      .fetch(existing.thread_id)
      .catch(() => null)) as ThreadChannel | null;
    if (thread) {
      await grantApplicantParentAccess(parent, member);
      await addApplicantToThread(thread, member);
      // Refresh the row (resets `reminded`/`delete_after`) so the reused
      // thread behaves like a freshly-opened one.
      await appDb
        .createApplicationThread({
          threadId: thread.id,
          applicantId: member.id,
          applicantUsername: member.user.username,
          guildId: member.guild.id,
          parentChannelId: parent.id,
        })
        .catch(() => null);
      await sendApplicationWelcome(thread, member);
      return thread;
    }
    // Stale row — the thread was deleted. Drop it and create a fresh one.
    await appDb
      .deleteApplicationThreadRow(existing.thread_id)
      .catch(() => null);
  }

  await grantApplicantParentAccess(parent, member);

  let thread: ThreadChannel;
  try {
    thread = await parent.threads.create({
      name: buildAppThreadName(member.user.username),
      type: ChannelType.PrivateThread,
      invitable: false,
      autoArchiveDuration: ThreadAutoArchiveDuration.OneWeek,
      reason: `Application thread for ${member.user.tag}`,
    });
  } catch (e) {
    logger.error("Application: failed to create private thread:", e);
    return null;
  }

  try {
    await appDb.createApplicationThread({
      threadId: thread.id,
      applicantId: member.id,
      applicantUsername: member.user.username,
      guildId: member.guild.id,
      parentChannelId: parent.id,
    });
  } catch (e) {
    logger.error("Application: failed to persist application thread:", e);
  }

  await addApplicantToThread(thread, member);
  await sendApplicationWelcome(thread, member);
  return thread;
}

/**
 * Tear down an application thread: optionally save a transcript, delete the
 * thread, revoke the applicant's parent-channel access, and drop the DB row.
 * Pass `transcriptReason = null` when a transcript was already saved (e.g. at
 * accept/deny time) to avoid duplicating it. Never throws.
 */
export async function finalizeApplicationThread(
  guild: Guild,
  row: ApplicationThread,
  transcriptReason: string | null,
): Promise<void> {
  const thread = (await guild.channels
    .fetch(row.thread_id)
    .catch(() => null)) as ThreadChannel | null;

  if (thread && transcriptReason) {
    const logChannel = (await guild.channels
      .fetch(config.LOG_CHANNEL_ID)
      .catch(() => null)) as TextChannel | null;
    if (logChannel) {
      if (thread.archived) await thread.setArchived(false).catch(() => null);
      await saveTranscriptToLog(thread, logChannel, transcriptReason).catch(
        () => null,
      );
    }
  }

  // Revoke the applicant's parent-channel access so the application channel
  // disappears from their sidebar.
  try {
    const parent = (await guild.channels
      .fetch(row.parent_channel_id)
      .catch(() => null)) as TextChannel | null;
    await parent?.permissionOverwrites
      .delete(row.applicant_id, "Application resolved")
      .catch(() => null);
  } catch (e) {
    logger.error("Application: failed to revoke applicant channel access:", e);
  }

  if (thread) {
    await thread
      .delete("Application resolved")
      .catch((e) => logger.error("Application: failed to delete thread:", e));
  }

  await appDb.deleteApplicationThreadRow(row.thread_id).catch(() => null);
}
