import type { Message, PartialMessage } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryPostDeletion,
} from "../utils/gallerySync.js";

export default class GalleryMessageDeleteEvent extends Event {
  constructor() {
    super("GalleryMessageDelete", "messageDelete", false);
  }

  async execute(message: Message | PartialMessage) {
    if (message.id !== message.channelId || !message.channel.isThread()) return;
    if (!isConfiguredGalleryThread(message.channel)) return;
    try {
      await queueGalleryPostDeletion(message.id);
    } catch (error) {
      logger.error(
        `[gallery] Failed to unpublish deleted starter ${message.id}: ${String(error)}`,
      );
    }
  }
}
