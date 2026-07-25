const recentBotDeletes = new Set<string>();
const TTL_MS = 30_000;

export function markBotDeleted(messageId: string): void {
  recentBotDeletes.add(messageId);
  const t = setTimeout(() => recentBotDeletes.delete(messageId), TTL_MS);
  if (typeof t === "object" && t && "unref" in t && typeof t.unref === "function") {
    t.unref();
  }
}

export function wasBotDeleted(messageId: string): boolean {
  return recentBotDeletes.has(messageId);
}
