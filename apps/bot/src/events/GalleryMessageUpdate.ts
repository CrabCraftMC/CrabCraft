import type { Message, PartialMessage } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryThreadSync,
} from "../utils/gallerySync.js";

export default class GalleryMessageUpdateEvent extends Event {
  constructor() {
    super("GalleryMessageUpdate", "messageUpdate", false);
  }

  async execute(
    _oldMessage: Message | PartialMessage,
    message: Message | PartialMessage,
  ) {
    if (message.id !== message.channelId || !message.channel.isThread()) return;
    if (!isConfiguredGalleryThread(message.channel)) return;

    try {
      await queueGalleryThreadSync(message.channel);
    } catch (error) {
      logger.error(
        `[gallery] Failed to sync edited starter message ${message.id}: ${String(error)}`,
      );
    }
  }
}
