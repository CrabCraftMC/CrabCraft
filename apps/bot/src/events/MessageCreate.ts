import Event from "../structures/Event.js";
import { type Message } from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { parseNumberFromMessage } from "../utils/counting.js";
import { withCountingQueue } from "../utils/countingQueue.js";
import { markBotDeleted } from "../utils/countingDeletes.js";
import { getCountingState, tryAdvanceCount } from "../utils/appDb.js";

export default class MessageCreateEvent extends Event {
  constructor() {
    super("MessageCreate", "messageCreate", false);
  }

  async execute(message: Message) {
    if (message.author.bot) return;
    if (!config.COUNTING_CHANNEL_ID) return;
    if (message.channelId !== config.COUNTING_CHANNEL_ID) return;

    const tag = `[counting] ${message.author.username} (${message.id})`;

    const queued = await withCountingQueue(message.channelId, async () => {
      try {
        const state = await getCountingState(message.channelId);
        const currentCount = state?.current_count ?? 0;
        const expected = currentCount + 1;

        if (state?.last_user_id === message.author.id) {
          logger.info(`${tag}: back-to-back from same user, deleting`);
          markBotDeleted(message.id);
          await message.delete().catch(() => null);
          return;
        }

        let number = await parseNumberFromMessage(message);
        let parsedFrom = number !== null ? "first-pass" : "miss";

        if (
          number === null &&
          message.attachments.size === 0 &&
          /https?:\/\//.test(message.content ?? "")
        ) {
          logger.info(`${tag}: no number on first pass, waiting 2s for embed`);
          await new Promise((r) => setTimeout(r, 2000));
          const refreshed = await message.fetch().catch(() => null);
          if (refreshed) {
            number = await parseNumberFromMessage(refreshed);
            parsedFrom = number !== null ? "after-refetch" : "miss-after-refetch";
          }
        }

        if (number === null) {
          logger.info(`${tag}: no number found (${parsedFrom}), deleting`);
          markBotDeleted(message.id);
          await message.delete().catch(() => null);
          return;
        }

        if (number !== expected) {
          logger.info(
            `${tag}: parsed ${number} (${parsedFrom}) but expected ${expected}, deleting`,
          );
          markBotDeleted(message.id);
          await message.delete().catch(() => null);
          return;
        }

        const advanced = await tryAdvanceCount(
          message.channelId,
          currentCount,
          message.author.id,
        );
        if (!advanced) {
          logger.warn(`${tag}: lost race for ${expected}, deleting`);
          markBotDeleted(message.id);
          await message.delete().catch(() => null);
          return;
        }

        logger.info(`${tag}: accepted ${expected} (${parsedFrom})`);
      } catch (error) {
        logger.error(`${tag}: handler crashed:`, error);
      }
    });
    if (!queued) {
      logger.warn(`${tag}: counting queue full, deleting`);
      markBotDeleted(message.id);
      await message.delete().catch(() => null);
    }
  }
}
