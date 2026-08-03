import type { MessageReaction, PartialMessageReaction } from "discord.js";
import Event from "../structures/Event.js";
import { syncGalleryReactionEvent } from "../utils/galleryReactionEvents.js";

export default class GalleryMessageReactionRemoveEmojiEvent extends Event {
  constructor() {
    super(
      "GalleryMessageReactionRemoveEmoji",
      "messageReactionRemoveEmoji",
      false,
    );
  }

  async execute(reaction: MessageReaction | PartialMessageReaction) {
    await syncGalleryReactionEvent(reaction.message, "an emoji being removed");
  }
}
