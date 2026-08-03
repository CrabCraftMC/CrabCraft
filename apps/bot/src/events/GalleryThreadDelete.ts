import type { AnyThreadChannel } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryPostDeletion,
} from "../utils/gallerySync.js";

export default class GalleryThreadDeleteEvent extends Event {
  constructor() {
    super("GalleryThreadDelete", "threadDelete", false);
  }

  async execute(thread: AnyThreadChannel) {
    if (!isConfiguredGalleryThread(thread)) return;
    try {
      await queueGalleryPostDeletion(thread.id);
    } catch (error) {
      logger.error(
        `[gallery] Failed to unpublish deleted thread ${thread.id}: ${String(error)}`,
      );
    }
  }
}
