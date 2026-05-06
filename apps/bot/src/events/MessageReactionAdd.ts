import Event from "../structures/Event.js";
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  MediaGalleryBuilder,
  MediaGalleryItemBuilder,
  MessageFlags,
  type MessageReaction,
  type PartialMessageReaction,
  type PartialUser,
  type TextChannel,
  type User,
} from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { STARBOARD_THRESHOLD } from "../utils/constants.js";

// Track messages we've already reposted this session to avoid duplicates
// when more reactions arrive after the threshold has been hit.
const starredMessages = new Set<string>();

export default class MessageReactionAddEvent extends Event {
  constructor() {
    super("MessageReactionAdd", "messageReactionAdd", false);
  }

  async execute(
    reaction: MessageReaction | PartialMessageReaction,
    user: User | PartialUser,
  ) {
    if (!config.STARBOARD_CHANNEL_ID) return;
    if (user.bot) return;

    try {
      if (reaction.partial) await reaction.fetch();
      if (reaction.message.partial) await reaction.message.fetch();
    } catch (error) {
      logger.error("Failed to fetch reaction/message:", error);
      return;
    }

    const message = reaction.message;
    if (!message.author || message.author.bot) return;
    if (message.channelId === config.STARBOARD_CHANNEL_ID) return;
    if (starredMessages.has(message.id)) return;

    // Aggregate distinct reactors across every reaction on the message,
    // excluding bots and the message author.
    const uniqueReactors = new Set<string>();
    for (const r of message.reactions.cache.values()) {
      try {
        const users = await r.users.fetch();
        for (const u of users.values()) {
          if (u.bot) continue;
          if (u.id === message.author.id) continue;
          uniqueReactors.add(u.id);
        }
      } catch (error) {
        logger.error("Failed to fetch reaction users:", error);
      }
    }

    if (uniqueReactors.size < STARBOARD_THRESHOLD) return;
    if (starredMessages.has(message.id)) return;
    starredMessages.add(message.id);

    const starboard = (await message.client.channels
      .fetch(config.STARBOARD_CHANNEL_ID)
      .catch(() => null)) as TextChannel | null;

    if (!starboard?.isTextBased()) {
      starredMessages.delete(message.id);
      logger.warn("Starboard channel not found or not text-based");
      return;
    }

    const imageAttachment = message.attachments.find((a) => {
      if (a.contentType?.startsWith("image/")) return true;
      return /\.(png|jpe?g|gif|webp)$/i.test(a.url);
    });

    const headerLines = [
      `### ⭐ Starred Message`,
      `**From:** <@${message.author.id}>`,
      `**Channel:** <#${message.channelId}>`,
    ];
    const text = message.content?.trim().length
      ? `${headerLines.join("\n")}\n\n${message.content}`
      : headerLines.join("\n");

    const container = new ContainerBuilder().addTextDisplayComponents((td) =>
      td.setContent(text),
    );

    if (imageAttachment) {
      container.addMediaGalleryComponents(
        new MediaGalleryBuilder().addItems(
          new MediaGalleryItemBuilder().setURL(imageAttachment.url),
        ),
      );
    }

    const linkRow = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setStyle(ButtonStyle.Link)
        .setLabel("View Original")
        .setURL(message.url),
    );

    try {
      await starboard.send({
        components: [container, linkRow],
        flags: MessageFlags.IsComponentsV2,
        allowedMentions: { parse: [] },
      });
    } catch (error) {
      starredMessages.delete(message.id);
      logger.error("Failed to send starboard message:", error);
    }
  }
}
