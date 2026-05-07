import Event from "../structures/Event.js";
import { type Message } from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { parseNumberFromMessage } from "../utils/counting.js";
import { getCountingState, tryAdvanceCount } from "../utils/appDb.js";

const queues = new Map<string, Promise<void>>();

function withQueue(key: string, fn: () => Promise<void>): Promise<void> {
  const next = (queues.get(key) ?? Promise.resolve())
    .catch(() => undefined)
    .then(fn);
  queues.set(key, next);
  return next;
}

export default class MessageCreateEvent extends Event {
  constructor() {
    super("MessageCreate", "messageCreate", false);
  }

  async execute(message: Message) {
    if (message.author.bot) return;
    if (!config.COUNTING_CHANNEL_ID) return;
    if (message.channelId !== config.COUNTING_CHANNEL_ID) return;

    await withQueue(message.channelId, async () => {
      try {
        const state = await getCountingState(message.channelId);
        const currentCount = state?.current_count ?? 0;

        if (state?.last_user_id === message.author.id) {
          await message.delete().catch(() => null);
          return;
        }

        let number = await parseNumberFromMessage(message);

        // Tenor/Giphy embeds often aren't attached at messageCreate time —
        // Discord scrapes the URL asynchronously. If the first parse missed
        // and the content has a URL, wait briefly and refetch.
        if (
          number === null &&
          message.attachments.size === 0 &&
          /https?:\/\//.test(message.content ?? "")
        ) {
          await new Promise((r) => setTimeout(r, 2000));
          const refreshed = await message.fetch().catch(() => null);
          if (refreshed) number = await parseNumberFromMessage(refreshed);
        }

        if (number === null || number !== currentCount + 1) {
          await message.delete().catch(() => null);
          return;
        }

        const advanced = await tryAdvanceCount(
          message.channelId,
          currentCount,
          message.author.id,
        );
        if (!advanced) {
          await message.delete().catch(() => null);
        }
      } catch (error) {
        logger.error("Counting handler failed:", error);
      }
    });
  }
}
