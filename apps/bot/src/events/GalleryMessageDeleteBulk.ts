import type {
  GuildTextBasedChannel,
  Message,
  PartialMessage,
} from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryPostDeletion,
} from "../utils/gallerySync.js";

export default class GalleryMessageDeleteBulkEvent extends Event {
  constructor() {
    super("GalleryMessageDeleteBulk", "messageDeleteBulk", false);
  }

  async execute(
    messages: ReadonlyMap<string, Message<true> | PartialMessage<true>>,
    channel: GuildTextBasedChannel,
  ) {
    if (!channel.isThread() || !messages.has(channel.id)) return;
    if (!isConfiguredGalleryThread(channel)) return;
    try {
      await queueGalleryPostDeletion(channel.id);
    } catch (error) {
      logger.error(
        `[gallery] Failed to unpublish bulk-deleted starter ${channel.id}: ${String(error)}`,
      );
    }
  }
}
