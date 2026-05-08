import Event from "../structures/Event.js";
import {
  ChannelType,
  type Message,
  type MessageReaction,
  type PartialMessageReaction,
  type PartialUser,
  type User,
} from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { STARBOARD_THRESHOLD } from "../utils/constants.js";
import {
  findTriggeringEmoji,
  isAllowedEmoji,
  isStarred,
  postToStarboard,
  scheduleStarboardUpdate,
} from "../utils/starboard.js";

export default class MessageReactionAddEvent extends Event {
  constructor() {
    super("MessageReactionAdd", "messageReactionAdd", false);
  }

  async execute(
    reaction: MessageReaction | PartialMessageReaction,
    user: User | PartialUser,
  ) {
    if (!config.STARBOARD_CHANNEL_ID) return;
    if (user.bot) return;
    if (!isAllowedEmoji(reaction.emoji, reaction.message.guild)) return;

    try {
      if (reaction.partial) await reaction.fetch();
      if (reaction.message.partial) await reaction.message.fetch();
    } catch (error) {
      logger.error("Failed to fetch reaction/message:", error);
      return;
    }

    const message = reaction.message;
    if (!message.author || message.author.bot) return;
    if (message.channelId === config.STARBOARD_CHANNEL_ID) return;
    if (message.channel?.type !== ChannelType.GuildText) return;

    if (await isStarred(message.id)) {
      scheduleStarboardUpdate(message);
      return;
    }

    const trigger = await findTriggeringEmoji(message, STARBOARD_THRESHOLD);
    if (!trigger) return;
    await postToStarboard(message as Message, trigger.emoji, trigger.count);
  }
}
