const ticketLifecycleLocks = new Map<number, Promise<void>>();

/** Serialize state transitions for one ticket within this bot process. */
export async function withTicketLifecycleLock<T>(
  ticketId: number,
  operation: () => Promise<T>,
): Promise<T> {
  // ponytail: this assumes one bot process; use a DB advisory lock if that changes.
  const previous = ticketLifecycleLocks.get(ticketId) ?? Promise.resolve();
  let release!: () => void;
  const current = new Promise<void>((resolve) => {
    release = resolve;
  });
  const tail = previous.then(() => current);
  ticketLifecycleLocks.set(ticketId, tail);

  await previous;
  try {
    return await operation();
  } finally {
    release();
    if (ticketLifecycleLocks.get(ticketId) === tail) {
      ticketLifecycleLocks.delete(ticketId);
    }
  }
}
