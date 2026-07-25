export async function withDeadline<T>(
  task: Promise<T>,
  timeoutMs: number,
): Promise<T> {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      task,
      new Promise<never>((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error("Stream check timed out")),
          timeoutMs,
        );
      }),
    ]);
  } finally {
    if (timeout) clearTimeout(timeout);
  }
}

export function singleFlight<TArgs extends unknown[]>(
  task: (...args: TArgs) => Promise<void>,
): (...args: TArgs) => Promise<boolean> {
  let running = false;

  return async (...args: TArgs): Promise<boolean> => {
    if (running) return false;
    running = true;
    try {
      await task(...args);
      return true;
    } finally {
      running = false;
    }
  };
}
