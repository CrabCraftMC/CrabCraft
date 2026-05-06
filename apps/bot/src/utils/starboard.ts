import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  MediaGalleryBuilder,
  MediaGalleryItemBuilder,
  MessageFlags,
  type Message,
  type PartialMessage,
  type TextChannel,
} from "discord.js";
import config from "./config.js";
import logger from "./logger.js";
import { STARBOARD_THRESHOLD, STARBOARD_UPDATE_DEBOUNCE_MS } from "./constants.js";
import {
  claimStarboardPost,
  deleteStarboardPost,
  getStarboardPost,
  hasStarboardPost,
  setStarboardMessageId,
} from "./appDb.js";

const pendingUpdates = new Map<string, NodeJS.Timeout>();
const starredCache = new Set<string>();

export async function isStarred(messageId: string): Promise<boolean> {
  if (starredCache.has(messageId)) return true;
  if (await hasStarboardPost(messageId)) {
    starredCache.add(messageId);
    return true;
  }
  return false;
}

export async function countUniqueReactors(
  message: Message | PartialMessage,
): Promise<number> {
  const reactors = new Set<string>();
  for (const r of message.reactions.cache.values()) {
    try {
      const users = await r.users.fetch();
      for (const u of users.values()) {
        if (u.bot) continue;
        if (u.id === message.author?.id) continue;
        reactors.add(u.id);
      }
    } catch (error) {
      logger.error("Failed to fetch reaction users:", error);
    }
  }
  return reactors.size;
}

interface ComponentOpts {
  authorId: string;
  channelId: string;
  messageUrl: string;
  content: string;
  imageUrl?: string;
  count: number;
}

function buildStarboardComponents(opts: ComponentOpts) {
  const headerLines = [
    `### ⭐ ${opts.count} · Starred Message`,
    `**From:** <@${opts.authorId}>`,
    `**Channel:** <#${opts.channelId}>`,
  ];
  const text = opts.content.trim().length
    ? `${headerLines.join("\n")}\n\n${opts.content}`
    : headerLines.join("\n");

  const container = new ContainerBuilder().addTextDisplayComponents((td) =>
    td.setContent(text),
  );

  if (opts.imageUrl) {
    container.addMediaGalleryComponents(
      new MediaGalleryBuilder().addItems(
        new MediaGalleryItemBuilder().setURL(opts.imageUrl),
      ),
    );
  }

  const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setStyle(ButtonStyle.Link)
      .setLabel("View Original")
      .setURL(opts.messageUrl),
  );

  return { container, row };
}

function findImageAttachment(message: Message): string | undefined {
  const att = message.attachments.find((a) => {
    if (a.contentType?.startsWith("image/")) return true;
    return /\.(png|jpe?g|gif|webp)$/i.test(a.url);
  });
  return att?.url;
}

export async function postToStarboard(message: Message): Promise<void> {
  if (!message.author) return;

  const claimed = await claimStarboardPost({
    messageId: message.id,
    channelId: message.channelId,
    authorId: message.author.id,
  });
  if (!claimed) {
    starredCache.add(message.id);
    return;
  }
  starredCache.add(message.id);

  const starboard = (await message.client.channels
    .fetch(config.STARBOARD_CHANNEL_ID)
    .catch(() => null)) as TextChannel | null;

  if (!starboard?.isTextBased()) {
    starredCache.delete(message.id);
    await deleteStarboardPost(message.id).catch(() => null);
    logger.warn("Starboard channel not found or not text-based");
    return;
  }

  const { container, row } = buildStarboardComponents({
    authorId: message.author.id,
    channelId: message.channelId,
    messageUrl: message.url,
    content: message.content ?? "",
    imageUrl: findImageAttachment(message),
    count: STARBOARD_THRESHOLD,
  });

  try {
    const sent = await starboard.send({
      components: [container, row],
      flags: MessageFlags.IsComponentsV2,
      allowedMentions: { parse: [] },
    });
    await setStarboardMessageId(message.id, sent.id).catch((e) =>
      logger.error("Failed to record starboard message id:", e),
    );
  } catch (error) {
    starredCache.delete(message.id);
    await deleteStarboardPost(message.id).catch(() => null);
    logger.error("Failed to send starboard message:", error);
  }
}

export function scheduleStarboardUpdate(message: Message | PartialMessage): void {
  const existing = pendingUpdates.get(message.id);
  if (existing) clearTimeout(existing);

  const timer = setTimeout(() => {
    pendingUpdates.delete(message.id);
    runStarboardUpdate(message).catch((e) =>
      logger.error("Starboard update failed:", e),
    );
  }, STARBOARD_UPDATE_DEBOUNCE_MS);

  pendingUpdates.set(message.id, timer);
}

async function runStarboardUpdate(
  message: Message | PartialMessage,
): Promise<void> {
  const post = await getStarboardPost(message.id);
  if (!post || !post.starboard_message_id) return;

  let full: Message;
  try {
    full = await message.fetch(true);
  } catch {
    logger.warn(`Source message ${message.id} no longer fetchable; leaving starboard post alone`);
    return;
  }
  if (!full.author) return;

  const count = await countUniqueReactors(full);

  const starboard = (await full.client.channels
    .fetch(config.STARBOARD_CHANNEL_ID)
    .catch(() => null)) as TextChannel | null;
  if (!starboard?.isTextBased()) return;

  const starMsg = await starboard.messages
    .fetch(post.starboard_message_id)
    .catch(() => null);
  if (!starMsg) {
    starredCache.delete(message.id);
    await deleteStarboardPost(message.id).catch(() => null);
    return;
  }

  const { container, row } = buildStarboardComponents({
    authorId: full.author.id,
    channelId: full.channelId,
    messageUrl: full.url,
    content: full.content ?? "",
    imageUrl: findImageAttachment(full),
    count,
  });

  try {
    await starMsg.edit({
      components: [container, row],
      flags: MessageFlags.IsComponentsV2,
      allowedMentions: { parse: [] },
    });
  } catch (error) {
    logger.error("Failed to edit starboard message:", error);
  }
}
