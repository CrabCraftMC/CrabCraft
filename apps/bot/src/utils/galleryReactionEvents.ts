import type { Message, PartialMessage } from "discord.js";
import logger from "./logger.js";
import {
  isConfiguredGalleryThread,
  queueGalleryReactionSync,
} from "./gallerySync.js";

export async function syncGalleryReactionEvent(
  message: Message | PartialMessage,
  action: string,
): Promise<void> {
  if (message.id !== message.channelId || !message.channel.isThread()) return;
  if (!isConfiguredGalleryThread(message.channel)) return;

  try {
    await queueGalleryReactionSync(message.channel);
  } catch (error) {
    logger.error(
      `[gallery] Failed to sync reactions after ${action} on ${message.id}: ${String(error)}`,
    );
  }
}
