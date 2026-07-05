import Event from "../structures/Event.js";
import { type Message } from "discord.js";
import logger from "../utils/logger.js";
import { CRAB_EMOJI_ID } from "../utils/constants.js";

const CRAB_PATTERN = /crab/i;

export default class CrabReactionEvent extends Event {
  constructor() {
    super("CrabReaction", "messageCreate", false);
  }

  async execute(message: Message) {
    if (message.author.bot) return;
    if (!message.inGuild()) return;
    if (!CRAB_PATTERN.test(message.content)) return;

    await message
      .react(CRAB_EMOJI_ID)
      .catch((e) => logger.warn("Failed to add crab reaction:", e));
  }
}
