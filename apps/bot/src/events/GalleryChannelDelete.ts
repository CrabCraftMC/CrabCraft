import type { Channel } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import * as appDb from "../utils/appDb.js";
import {
  allocateGallerySyncRevision,
  getGalleryChannelConfig,
} from "../utils/gallerySync.js";

export default class GalleryChannelDeleteEvent extends Event {
  constructor() {
    super("GalleryChannelDelete", "channelDelete", false);
  }

  async execute(channel: Channel) {
    if (!getGalleryChannelConfig(channel.id)) return;
    try {
      const revision = await allocateGallerySyncRevision();
      const deletedAt = Math.floor(Date.now() / 1000);
      await appDb.markGalleryChannelDeleted(
        channel.id,
        deletedAt,
        revision,
      );
    } catch (error) {
      logger.error(
        `[gallery] Failed to unpublish deleted channel ${channel.id}: ${String(error)}`,
      );
    }
  }
}
