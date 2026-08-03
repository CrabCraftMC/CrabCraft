import type { Collection, Message, MessageReaction, Snowflake } from "discord.js";
import Event from "../structures/Event.js";
import { syncGalleryReactionEvent } from "../utils/galleryReactionEvents.js";

export default class GalleryMessageReactionRemoveAllEvent extends Event {
  constructor() {
    super("GalleryMessageReactionRemoveAll", "messageReactionRemoveAll", false);
  }

  async execute(
    message: Message,
    _reactions: Collection<Snowflake | string, MessageReaction>,
  ) {
    await syncGalleryReactionEvent(message, "all reactions being removed");
  }
}
