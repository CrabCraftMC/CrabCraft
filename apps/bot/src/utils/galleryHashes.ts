import { createHash } from "node:crypto";
import type {
  GalleryImageSyncInput,
  GalleryTagSyncInput,
} from "./galleryTypes.js";

function hashSnapshot(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

export function buildGalleryTagsHash(
  tags: readonly GalleryTagSyncInput[],
): string {
  return hashSnapshot(
    tags.map((tag) => ({
      discordTagId: tag.discordTagId,
      name: tag.name,
      emojiId: tag.emojiId,
      emojiName: tag.emojiName,
      position: tag.position,
    })),
  );
}

export function buildGalleryPostContentHash(snapshot: {
  seasonId: string;
  title: string;
  content: string | null;
  authorDiscordId: string;
  authorDiscordUsername: string;
  authorDisplayName: string | null;
  postedAt: number;
  tags: readonly GalleryTagSyncInput[];
  images: readonly GalleryImageSyncInput[];
}): string {
  return hashSnapshot({
    seasonId: snapshot.seasonId,
    title: snapshot.title,
    content: snapshot.content,
    authorDiscordId: snapshot.authorDiscordId,
    authorDiscordUsername: snapshot.authorDiscordUsername,
    authorDisplayName: snapshot.authorDisplayName,
    postedAt: snapshot.postedAt,
    tags: [...snapshot.tags]
      .sort(
        (left, right) =>
          left.position - right.position ||
          left.discordTagId.localeCompare(right.discordTagId),
      )
      .map((tag) => ({
        discordTagId: tag.discordTagId,
        name: tag.name,
        emojiId: tag.emojiId,
        emojiName: tag.emojiName,
      })),
    images: [...snapshot.images]
      .sort((left, right) => left.position - right.position)
      .map((image) => ({
        discordAttachmentId: image.discordAttachmentId,
        alt: image.alt,
        width: image.width,
        height: image.height,
      })),
  });
}
