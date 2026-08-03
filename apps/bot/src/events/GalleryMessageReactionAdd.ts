import type {
  MessageReaction,
  PartialMessageReaction,
  PartialUser,
  User,
} from "discord.js";
import Event from "../structures/Event.js";
import { syncGalleryReactionEvent } from "../utils/galleryReactionEvents.js";

export default class GalleryMessageReactionAddEvent extends Event {
  constructor() {
    super("GalleryMessageReactionAdd", "messageReactionAdd", false);
  }

  async execute(
    reaction: MessageReaction | PartialMessageReaction,
    _user: User | PartialUser,
  ) {
    await syncGalleryReactionEvent(reaction.message, "a reaction add");
  }
}
