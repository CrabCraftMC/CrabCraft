import type { GalleryChannelConfig } from "./config.js";
import type { GalleryTagSyncInput } from "./galleryTypes.js";

export function findGalleryChannelConfig(
  channels: readonly GalleryChannelConfig[],
  channelId: string | null,
): GalleryChannelConfig | null {
  if (!channelId) return null;
  return channels.find((channel) => channel.channelId === channelId) ?? null;
}

export function resolveAppliedGalleryTags(
  appliedTagIds: readonly string[],
  availableTags: readonly GalleryTagSyncInput[],
): GalleryTagSyncInput[] {
  const tagsById = new Map(availableTags.map((tag) => [tag.discordTagId, tag]));
  return appliedTagIds.map(
    (discordTagId, appliedPosition): GalleryTagSyncInput =>
      tagsById.get(discordTagId) ?? {
        discordTagId,
        name: "Unknown tag",
        emojiId: null,
        emojiName: null,
        position: availableTags.length + appliedPosition,
        moderated: false,
      },
  );
}
