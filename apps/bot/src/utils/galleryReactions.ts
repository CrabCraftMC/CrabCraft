import type { Message } from "discord.js";
import type { GalleryReactionSyncInput } from "./galleryTypes.js";

export function getGalleryReactionSnapshot(
  message: Pick<Message, "reactions">,
): GalleryReactionSyncInput[] {
  return [...message.reactions.cache.values()]
    .filter((reaction) => reaction.count > 0)
    .map((reaction) => {
      const emojiId = reaction.emoji.id;
      const emojiName = reaction.emoji.name ?? "Unknown emoji";
      return {
        emojiKey: emojiId ? `custom:${emojiId}` : `unicode:${emojiName}`,
        emojiId,
        emojiName,
        animated: Boolean(reaction.emoji.animated),
        count: reaction.count,
      };
    })
    .sort(
      (left, right) =>
        right.count - left.count || left.emojiKey.localeCompare(right.emojiKey),
    );
}
