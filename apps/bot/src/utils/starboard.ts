import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  MediaGalleryBuilder,
  MediaGalleryItemBuilder,
  MessageFlags,
  StickerFormatType,
  type Guild,
  type Message,
  type PartialMessage,
  type TextChannel,
} from "discord.js";
import config from "./config.js";
import logger from "./logger.js";
import { STARBOARD_UPDATE_DEBOUNCE_MS } from "./constants.js";
import {
  claimStarboardPost,
  deleteStarboardPost,
  getStarboardPost,
  hasStarboardPost,
  setStarboardMessageId,
} from "./appDb.js";

const pendingUpdates = new Map<string, NodeJS.Timeout>();
const starredCache = new Set<string>();

// Fallback shown on the legacy starboard rows that predate per-emoji tracking.
const LEGACY_DISPLAY_EMOJI = "<:star:1423390709448314880>";

export interface TriggerEmoji {
  id: string | null;
  name: string | null;
  animated: boolean;
}

/**
 * Allow a reaction emoji only if it's a native unicode emoji or a custom
 * emoji that belongs to this guild. Foreign custom emojis (from other
 * servers the user has Nitro for) are rejected.
 */
export function isAllowedEmoji(
  emoji: { id: string | null; name: string | null },
  guild: Guild | null,
): boolean {
  if (!emoji.id) return emoji.name !== null;
  if (!guild) return false;
  return guild.emojis.cache.has(emoji.id);
}

export function formatEmojiDisplay(emoji: TriggerEmoji): string {
  if (!emoji.id) return emoji.name ?? LEGACY_DISPLAY_EMOJI;
  if (!emoji.name) return LEGACY_DISPLAY_EMOJI;
  return emoji.animated
    ? `<a:${emoji.name}:${emoji.id}>`
    : `<:${emoji.name}:${emoji.id}>`;
}

function emojiMatches(
  a: { id: string | null; name: string | null },
  b: { id: string | null; name: string | null },
): boolean {
  if (a.id || b.id) return a.id === b.id;
  return a.name === b.name;
}

export async function isStarred(messageId: string): Promise<boolean> {
  if (starredCache.has(messageId)) return true;
  if (await hasStarboardPost(messageId)) {
    starredCache.add(messageId);
    return true;
  }
  return false;
}

/**
 * Find the first emoji on the message that has reached `threshold` unique
 * non-author reactors. Foreign custom emojis are ignored.
 */
export async function findTriggeringEmoji(
  message: Message | PartialMessage,
  threshold: number,
): Promise<{ emoji: TriggerEmoji; count: number } | null> {
  const guild = message.guild;
  for (const r of message.reactions.cache.values()) {
    if (!isAllowedEmoji(r.emoji, guild)) continue;
    const reactors = new Set<string>();
    try {
      const users = await r.users.fetch();
      for (const u of users.values()) {
        if (u.bot) continue;
        if (u.id === message.author?.id) continue;
        reactors.add(u.id);
      }
    } catch (error) {
      logger.error("Failed to fetch reaction users:", error);
      continue;
    }
    if (reactors.size >= threshold) {
      return {
        emoji: {
          id: r.emoji.id,
          name: r.emoji.name,
          animated: r.emoji.animated ?? false,
        },
        count: reactors.size,
      };
    }
  }
  return null;
}

async function countReactorsForEmoji(
  message: Message | PartialMessage,
  emoji: TriggerEmoji,
): Promise<number> {
  const reactors = new Set<string>();
  for (const r of message.reactions.cache.values()) {
    if (!emojiMatches(r.emoji, emoji)) continue;
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

/**
 * React to the starred message with its trigger emoji, skipping the API
 * call if the bot has already reacted. Bot reactions are excluded from
 * reactor counts, so this can't re-trigger or inflate the starboard tally.
 */
async function ensureBotReaction(
  message: Message,
  emoji: TriggerEmoji,
): Promise<void> {
  const reactionEmoji = emoji.id ?? emoji.name;
  if (!reactionEmoji) return;
  const existing = message.reactions.cache.find(
    (r) => emojiMatches(r.emoji, emoji) && r.me,
  );
  if (existing) return;
  await message
    .react(reactionEmoji)
    .catch((e) => logger.warn("Failed to react to starred message:", e));
}

interface ComponentOpts {
  authorId: string;
  channelId: string;
  messageUrl: string;
  content: string;
  imageUrl?: string;
  count: number;
  emojiDisplay: string;
}

function buildStarboardComponents(opts: ComponentOpts) {
  const headerLines = [
    `### ${opts.emojiDisplay} ${opts.count} · Starred Message`,
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

interface MessageMedia {
  imageUrl?: string;
  contentSuffix?: string;
}

function extractMessageMedia(message: Message): MessageMedia {
  const att = message.attachments.find((a) => {
    if (a.contentType?.startsWith("image/")) return true;
    return /\.(png|jpe?g|gif|webp)$/i.test(a.url);
  });
  if (att) return { imageUrl: att.url };

  // Lottie stickers have no static URL Discord embeds can render; fall back
  // to a text note. PNG/APNG/GIF stickers expose a usable CDN URL via Sticker#url.
  const renderable = message.stickers.find(
    (s) => s.format !== StickerFormatType.Lottie,
  );
  if (renderable) return { imageUrl: renderable.url };

  const lottie = message.stickers.first();
  if (lottie) return { contentSuffix: `*[Sticker: ${lottie.name}]*` };

  return {};
}

function applyMediaToContent(
  content: string,
  suffix: string | undefined,
): string {
  const trimmed = content.trim();
  if (!suffix) return trimmed;
  return trimmed.length ? `${trimmed}\n\n${suffix}` : suffix;
}

export async function postToStarboard(
  message: Message,
  triggerEmoji: TriggerEmoji,
  count: number,
): Promise<void> {
  if (!message.author) return;

  const claimed = await claimStarboardPost({
    messageId: message.id,
    channelId: message.channelId,
    authorId: message.author.id,
    triggerEmojiId: triggerEmoji.id,
    triggerEmojiName: triggerEmoji.name,
    triggerEmojiAnimated: triggerEmoji.animated,
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

  const media = extractMessageMedia(message);
  const { container, row } = buildStarboardComponents({
    authorId: message.author.id,
    channelId: message.channelId,
    messageUrl: message.url,
    content: applyMediaToContent(message.content ?? "", media.contentSuffix),
    imageUrl: media.imageUrl,
    count,
    emojiDisplay: formatEmojiDisplay(triggerEmoji),
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
    await ensureBotReaction(message, triggerEmoji);
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

  const triggerEmoji: TriggerEmoji = {
    id: post.trigger_emoji_id,
    name: post.trigger_emoji_name,
    animated: post.trigger_emoji_animated,
  };
  // Legacy rows have no recorded emoji; fall back to the original star.
  const hasRecordedEmoji =
    post.trigger_emoji_id !== null || post.trigger_emoji_name !== null;
  const count = hasRecordedEmoji
    ? await countReactorsForEmoji(full, triggerEmoji)
    : 0;

  // Backfill the bot's reaction on messages starred before reacting was
  // introduced. Legacy rows have no recorded emoji, so they're skipped.
  if (hasRecordedEmoji) await ensureBotReaction(full, triggerEmoji);

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

  const media = extractMessageMedia(full);
  const { container, row } = buildStarboardComponents({
    authorId: full.author.id,
    channelId: full.channelId,
    messageUrl: full.url,
    content: applyMediaToContent(full.content ?? "", media.contentSuffix),
    imageUrl: media.imageUrl,
    count,
    emojiDisplay: formatEmojiDisplay(triggerEmoji),
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
