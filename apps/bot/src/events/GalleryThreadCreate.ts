import type { AnyThreadChannel } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  getGalleryChannelConfig,
  isConfiguredGalleryThread,
  queueGalleryThreadSync,
} from "../utils/gallerySync.js";
import { AnalyticsEvent } from "@crabcraft/shared/analytics";
import { captureDiscordEvent } from "../utils/analytics.js";

export default class GalleryThreadCreateEvent extends Event {
  constructor() {
    super("GalleryThreadCreate", "threadCreate", false);
  }

  async execute(thread: AnyThreadChannel, newlyCreated: boolean) {
    if (!isConfiguredGalleryThread(thread)) return;
    try {
      const result = await queueGalleryThreadSync(thread, {
        retryStarter: newlyCreated,
      });
      const gallery = getGalleryChannelConfig(thread.parentId);
      if (newlyCreated && result === "synced" && thread.ownerId) {
        void captureDiscordEvent(
          thread.ownerId,
          AnalyticsEvent.GALLERY_POST_PUBLISHED,
          { season: gallery?.seasonId ?? null },
          { dedupeKey: thread.id },
        );
      }
    } catch (error) {
      logger.error(
        `[gallery] Failed to sync created thread ${thread.id}: ${String(error)}`,
      );
    }
  }
}
