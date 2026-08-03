import type { AnyThreadChannel } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryThreadSync,
} from "../utils/gallerySync.js";

export default class GalleryThreadUpdateEvent extends Event {
  constructor() {
    super("GalleryThreadUpdate", "threadUpdate", false);
  }

  async execute(_oldThread: AnyThreadChannel, thread: AnyThreadChannel) {
    if (!isConfiguredGalleryThread(thread)) return;
    try {
      await queueGalleryThreadSync(thread);
    } catch (error) {
      logger.error(
        `[gallery] Failed to sync updated thread ${thread.id}: ${String(error)}`,
      );
    }
  }
}
