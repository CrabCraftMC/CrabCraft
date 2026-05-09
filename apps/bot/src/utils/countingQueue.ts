const queues = new Map<string, Promise<void>>();

export function withCountingQueue(
  channelId: string,
  fn: () => Promise<void>,
): Promise<void> {
  const next = (queues.get(channelId) ?? Promise.resolve())
    .catch(() => undefined)
    .then(fn);
  queues.set(channelId, next);
  return next;
}
