import type { AnyThreadChannel, Message } from "discord.js";
import { GALLERY_STARTER_RETRY_DELAYS_MS } from "./constants.js";
import { isUnknownMessageError } from "./discordErrors.js";

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export async function fetchGalleryStarterMessage(
  thread: AnyThreadChannel,
  retry: boolean,
  retryDelays?: readonly number[],
): Promise<Message<true> | null> {
  const delays =
    retryDelays ?? (retry ? GALLERY_STARTER_RETRY_DELAYS_MS : ([0] as const));
  let lastError: unknown;

  for (const waitMs of delays) {
    if (waitMs > 0) await delay(waitMs);
    try {
      const starter = await thread.fetchStarterMessage({
        cache: false,
        force: true,
      });
      if (starter) return starter;
    } catch (error) {
      if (isUnknownMessageError(error)) {
        if (!retry) return null;
        // Newly created forum/media starters can briefly return 10008 while
        // Discord finishes propagation. Only treat it as authoritative after
        // the configured creation retries are exhausted.
        lastError = error;
        continue;
      }
      lastError = error;
    }
  }

  if (isUnknownMessageError(lastError)) return null;
  if (lastError) throw lastError;
  throw new Error(`Starter message for thread ${thread.id} was unavailable.`);
}
