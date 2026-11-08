/** In-memory store for partial application data while the user retries their username. */
export interface FullAppData {
  type: "full";
  age: number;
  joinReason: string;
  aboutYou: string;
  referralSource: string;
}

const store = new Map<string, FullAppData>();

export function storeRetry(userId: string, data: FullAppData) {
  store.set(userId, data);
}

export function getRetry(userId: string): FullAppData | null {
  const data = store.get(userId) ?? null;
  store.delete(userId);
  return data;
}

export function clearRetry(userId: string) {
  store.delete(userId);
}
