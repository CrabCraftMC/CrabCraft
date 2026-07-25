const MAX_PENDING_PER_CHANNEL = 25;

interface QueueState {
  tail: Promise<void>;
  pending: number;
}

const queues = new Map<string, QueueState>();

export async function withCountingQueue(
  channelId: string,
  fn: () => Promise<void>,
): Promise<boolean> {
  const state = queues.get(channelId) ?? {
    tail: Promise.resolve(),
    pending: 0,
  };
  if (state.pending >= MAX_PENDING_PER_CHANNEL) return false;

  state.pending++;
  const next = state.tail
    .catch(() => undefined)
    .then(fn)
    .finally(() => {
      state.pending--;
      if (state.pending === 0 && state.tail === next) {
        queues.delete(channelId);
      }
    });
  state.tail = next;
  queues.set(channelId, state);
  await next;
  return true;
}
