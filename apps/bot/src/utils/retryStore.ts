/** In-memory store for partial application data while the user retries their username. */
import { RETRY_EXPIRY_MS } from "./constants.js";

export interface FullAppData {
  type: "full";
  age: string;
  ingameVoice: string;
  joinReason: string;
  favouriteWood: string;
}

type RetryEntry = {
  data: FullAppData;
  timer: Timer;
};

const store = new Map<string, RetryEntry>();

export function storeRetry(userId: string, data: FullAppData) {
  clearRetry(userId);
  const timer = setTimeout(() => store.delete(userId), RETRY_EXPIRY_MS);
  store.set(userId, { data, timer });
}

export function getRetry(userId: string): FullAppData | null {
  const entry = store.get(userId);
  if (!entry) return null;
  clearTimeout(entry.timer);
  store.delete(userId);
  return entry.data;
}

export function clearRetry(userId: string) {
  const entry = store.get(userId);
  if (entry) {
    clearTimeout(entry.timer);
    store.delete(userId);
  }
}
