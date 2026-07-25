import Event from "../structures/Event.js";
import { type Message, type PartialMessage } from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { parseNumberFromMessage } from "../utils/counting.js";
import { withCountingQueue } from "../utils/countingQueue.js";
import { wasBotDeleted } from "../utils/countingDeletes.js";
import { getCountingState } from "../utils/appDb.js";

export default class MessageDeleteEvent extends Event {
  constructor() {
    super("MessageDelete", "messageDelete", false);
  }

  async execute(message: Message | PartialMessage) {
    if (!config.COUNTING_CHANNEL_ID) return;
    if (message.channelId !== config.COUNTING_CHANNEL_ID) return;
    if (wasBotDeleted(message.id)) return;
    if (message.partial) return;
    if (!message.author || message.author.bot) return;

    const tag = `[counting] resend on delete ${message.author.username} (${message.id})`;

    await withCountingQueue(message.channelId, async () => {
      try {
        const state = await getCountingState(message.channelId);
        if (!state) return;
        if (state.last_user_id !== message.author!.id) return;

        const number = await parseNumberFromMessage(message);
        if (number === null || number !== state.current_count) return;

        const channel = message.channel;
        if (!channel?.isSendable()) return;

        await channel.send({
          content: String(state.current_count),
          allowedMentions: { parse: [] },
        });
        logger.info(`${tag}: reposted ${state.current_count}`);
      } catch (error) {
        logger.error(`${tag}: handler crashed:`, error);
      }
    });
  }
}
