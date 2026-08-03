import type { Message } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryThreadSync,
} from "../utils/gallerySync.js";

export default class GalleryMessageCreateEvent extends Event {
  constructor() {
    super("GalleryMessageCreate", "messageCreate", false);
  }

  async execute(message: Message) {
    if (message.id !== message.channelId || !message.channel.isThread()) return;
    if (!isConfiguredGalleryThread(message.channel)) return;
    try {
      await queueGalleryThreadSync(message.channel, { retryStarter: true });
    } catch (error) {
      logger.error(
        `[gallery] Failed to sync starter message ${message.id}: ${String(error)}`,
      );
    }
  }
}
