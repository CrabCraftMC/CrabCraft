import Event from "../structures/Event.js";
import type { Message, PartialMessage } from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { getCountingState } from "../utils/appDb.js";
import { parseNumberFromMessage } from "../utils/counting.js";
import { markBotDeleted } from "../utils/countingDeletes.js";
import { withCountingQueue } from "../utils/countingQueue.js";

export default class MessageUpdateEvent extends Event {
  constructor() {
    super("MessageUpdate", "messageUpdate", false);
  }

  async execute(_oldMessage: Message | PartialMessage, updated: Message | PartialMessage) {
    if (!config.COUNTING_CHANNEL_ID) return;
    if (updated.channelId !== config.COUNTING_CHANNEL_ID) return;
    if (updated.guildId !== config.GUILD_ID) return;

    const message = updated.partial
      ? await updated.fetch().catch(() => null)
      : updated;
    if (!message?.author || message.author.bot) return;

    const queued = await withCountingQueue(message.channelId, async () => {
      const state = await getCountingState(message.channelId);
      if (!state || state.last_user_id !== message.author.id) return;

      const newer = await message.channel.messages
        .fetch({ after: message.id, limit: 100 })
        .catch(() => null);
      if (!newer || newer.some((candidate) => !candidate.author.bot)) return;

      const number = await parseNumberFromMessage(message);
      if (number === state.current_count) return;

      markBotDeleted(message.id);
      await message.delete().catch(() => null);
      if (message.channel.isSendable()) {
        await message.channel.send({
          content: String(state.current_count),
          allowedMentions: { parse: [] },
        });
      }
      logger.info(
        `[counting] removed invalid edit from ${message.author.username} (${message.id})`,
      );
    });
    if (!queued) {
      markBotDeleted(message.id);
      await message.delete().catch(() => null);
      logger.warn(`[counting] queue full while validating edit ${message.id}; deleted`);
    }
  }
}
