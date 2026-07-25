import Event from "../structures/Event.js";
import {
  ChannelType,
  type MessageReaction,
  type PartialMessageReaction,
  type PartialUser,
  type User,
} from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import {
  isAllowedEmoji,
  isStarred,
  scheduleStarboardUpdate,
} from "../utils/starboard.js";

export default class MessageReactionRemoveEvent extends Event {
  constructor() {
    super("MessageReactionRemove", "messageReactionRemove", false);
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
      logger.error("Failed to fetch reaction/message on remove:", error);
      return;
    }

    const message = reaction.message;
    const guild = message.guild;
    if (!guild || guild.id !== config.GUILD_ID) return;
    if (message.channelId === config.STARBOARD_CHANNEL_ID) return;
    if (message.channel?.type !== ChannelType.GuildText) return;
    if (!message.channel.permissionsFor(guild.roles.everyone)
      ?.has("ViewChannel")) return;
    if (!(await isStarred(message.id))) return;

    scheduleStarboardUpdate(message);
  }
}
