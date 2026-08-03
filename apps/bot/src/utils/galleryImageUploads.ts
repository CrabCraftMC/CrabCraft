import type { Attachment } from "discord.js";
import * as appDb from "./appDb.js";
import type { GalleryStorage } from "./galleryStorage.js";
import { inferGalleryImageContentType } from "./galleryStorageHelpers.js";
import type { GalleryImageSyncInput } from "./galleryTypes.js";
import logger from "./logger.js";

type GalleryAttachment = Pick<
  Attachment,
  | "id"
  | "url"
  | "name"
  | "title"
  | "description"
  | "contentType"
  | "size"
  | "width"
  | "height"
>;

type EnqueueUnreferencedImages = (
  images: Array<{ storageKey: string; publicUrl: string }>,
  queuedAt: number,
) => Promise<number>;

let pendingGalleryImageStore: Promise<unknown> = Promise.resolve();

export function queueGalleryStorageWrite<T>(
  operation: () => Promise<T>,
): Promise<T> {
  const next = pendingGalleryImageStore
    .catch(() => undefined)
    .then(operation);
  pendingGalleryImageStore = next;
  void next.catch(() => undefined);
  return next;
}

async function enqueueStoredImagesForCleanup(
  images: readonly GalleryImageSyncInput[],
  queuedAt: number,
  threadId: string,
  context: string,
  enqueueUnreferenced: EnqueueUnreferencedImages,
): Promise<void> {
  if (images.length === 0) return;
  try {
    await enqueueUnreferenced(
      images.map(({ storageKey, publicUrl }) => ({ storageKey, publicUrl })),
      queuedAt,
    );
  } catch (cleanupError) {
    logger.error(
      `[gallery] Failed to queue ${images.length} ${context} upload(s) for thread ${threadId}: ${String(cleanupError)}`,
    );
  }
}

export async function persistStoredGalleryImages<T>(
  images: readonly GalleryImageSyncInput[],
  queuedAt: number,
  threadId: string,
  persist: () => Promise<T>,
  enqueueUnreferenced: EnqueueUnreferencedImages =
    appDb.enqueueUnreferencedGalleryStorageDeletions,
): Promise<T> {
  try {
    return await persist();
  } catch (error) {
    await enqueueStoredImagesForCleanup(
      images,
      queuedAt,
      threadId,
      "unpersisted",
      enqueueUnreferenced,
    );
    throw error;
  }
}

export async function storeGalleryImages(
  storage: GalleryStorage,
  seasonId: string,
  threadId: string,
  attachments: readonly GalleryAttachment[],
  queuedAt: number,
  enqueueUnreferenced: EnqueueUnreferencedImages =
    appDb.enqueueUnreferencedGalleryStorageDeletions,
): Promise<GalleryImageSyncInput[]> {
  const images: GalleryImageSyncInput[] = [];
  try {
    for (const [position, attachment] of attachments.entries()) {
      const contentType = inferGalleryImageContentType(
        attachment.contentType,
        attachment.name,
      );
      if (!contentType) {
        throw new Error(`Attachment ${attachment.id} is not a supported image.`);
      }

      const stored = await storage.store(seasonId, threadId, {
        id: attachment.id,
        url: attachment.url,
        filename: attachment.name,
        contentType: attachment.contentType,
        size: attachment.size,
        width: attachment.width,
        height: attachment.height,
      });

      images.push({
        discordAttachmentId: attachment.id,
        position,
        storageKey: stored.storageKey,
        publicUrl: stored.publicUrl,
        filename: attachment.title ?? attachment.name,
        alt: attachment.description,
        contentType,
        width: stored.width,
        height: stored.height,
        size: attachment.size,
      });
    }
  } catch (error) {
    await enqueueStoredImagesForCleanup(
      images,
      queuedAt,
      threadId,
      "partial",
      enqueueUnreferenced,
    );
    throw error;
  }
  return images;
}
