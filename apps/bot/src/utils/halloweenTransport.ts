import Redis from "ioredis";
import { getMinecraftHalloweenProgress, recordHalloweenAction } from "@crabcraft/db/queries/halloween";
import { parseHalloweenAction } from "@crabcraft/db/halloweenLogic";
import logger from "./logger.js";

const STREAM = "crabcraft:halloween:actions";
const GROUP = "crabcraft-bot-halloween";
type StreamResponse = Array<[string, Array<[string, string[]]>]>;

export async function consumeHalloweenActions(redis: Redis) {
  // One ordered consumer: death resets must never overtake earlier kills.
  const consumer = "crabcraft-bot";
  while (true) {
    try {
      try {
        await redis.xgroup("CREATE", STREAM, GROUP, "0", "MKSTREAM");
      } catch (error) {
        if (!(error as Error).message.includes("BUSYGROUP")) throw error;
      }
      while (true) {
        const read = redis.xreadgroup.bind(redis) as (...args: unknown[]) => Promise<StreamResponse | null>;
        const pending = await read("GROUP", GROUP, consumer, "COUNT", 100, "STREAMS", STREAM, "0");
        const response = pending?.some(([, entries]) => entries.length)
          ? pending
          : await read("GROUP", GROUP, consumer, "BLOCK", 10_000, "COUNT", 100, "STREAMS", STREAM, ">");
        for (const [, entries] of response ?? []) {
          for (const [id, raw] of entries) {
            const fields: Record<string, string> = {};
            for (let index = 0; index < raw.length - 1; index += 2) fields[raw[index]] = raw[index + 1];
            const event = parseHalloweenAction(fields);
            if (event) {
              const completion = await recordHalloweenAction(event, id);
              if (completion) await redis.publish("crabcraft:halloween:completions", JSON.stringify(completion));
            } else logger.warn(`Ignoring malformed Halloween action ${id}`);
            // Only remove actions after their PostgreSQL transaction commits.
            await redis.multi().xack(STREAM, GROUP, id).xdel(STREAM, id).exec();
          }
        }
      }
    } catch (error) {
      logger.warn(`Halloween action intake will retry: ${(error as Error).message}`);
      await new Promise((resolve) => setTimeout(resolve, 3_000));
    }
  }
}


// Separate connection from the action stream: both consumers use blocking reads.
export async function consumeHalloweenProgressRequests(redis: Redis, eventId: string, enabled: boolean) {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  while (true) {
    try {
      const request = await redis.blpop("crabcraft:halloween:progress-requests", 10);
      if (!request) continue;
      const fields = JSON.parse(request[1]);
      if (!uuid.test(fields.requestId ?? "") || !uuid.test(fields.playerId ?? "")
        || !Number.isSafeInteger(fields.expiresAt) || fields.expiresAt < Date.now()) continue;
      const progress = enabled ? await getMinecraftHalloweenProgress(eventId, fields.playerId.toLowerCase()) : null;
      const key = `crabcraft:halloween:progress-reply:${fields.requestId}`;
      await redis.multi().rpush(key, JSON.stringify(progress ?? { unavailable: true })).expire(key, 30).exec();
    } catch (error) {
      logger.warn(`Halloween progress request failed: ${(error as Error).message}`);
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
  }
}
