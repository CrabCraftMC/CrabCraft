import type { Channel } from "discord.js";
import Event from "../structures/Event.js";
import { getGalleryStorage } from "../utils/galleryStorage.js";
import logger from "../utils/logger.js";
import {
  allocateGallerySyncRevision,
  getGalleryChannelConfig,
  isGalleryParentChannel,
  syncGalleryChannelTags,
} from "../utils/gallerySync.js";

export default class GalleryChannelUpdateEvent extends Event {
  constructor() {
    super("GalleryChannelUpdate", "channelUpdate", false);
  }

  async execute(_oldChannel: Channel, channel: Channel) {
    if (!getGalleryChannelConfig(channel.id) || !isGalleryParentChannel(channel)) {
      return;
    }
    try {
      const revision = await allocateGallerySyncRevision();
      // Do not mutate gallery state when its durable media store is disabled.
      getGalleryStorage();
      await syncGalleryChannelTags(channel, revision);
    } catch (error) {
      logger.error(
        `[gallery] Failed to sync tags for channel ${channel.id}: ${String(error)}`,
      );
    }
  }
}
