import type { AnyThreadChannel } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryThreadSync,
} from "../utils/gallerySync.js";

export default class GalleryThreadCreateEvent extends Event {
  constructor() {
    super("GalleryThreadCreate", "threadCreate", false);
  }

  async execute(thread: AnyThreadChannel, newlyCreated: boolean) {
    if (!isConfiguredGalleryThread(thread)) return;
    try {
      await queueGalleryThreadSync(thread, { retryStarter: newlyCreated });
    } catch (error) {
      logger.error(
        `[gallery] Failed to sync created thread ${thread.id}: ${String(error)}`,
      );
    }
  }
}
