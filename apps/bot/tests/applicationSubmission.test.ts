import { expect, mock, test } from "bun:test";
import { randomInt, randomUUID } from "node:crypto";

// Keep these module mocks out of other bot tests that load the same handlers.
if (process.env.CRABCRAFT_TEST_APPLICATION_SUBMISSION_CHILD !== "1") {
  test("application modals acknowledge promptly and retain their source until replacement succeeds", () => {
    const result = Bun.spawnSync([process.execPath, import.meta.path], {
      env: { ...process.env, CRABCRAFT_TEST_APPLICATION_SUBMISSION_CHILD: "1" },
      timeout: 10_000,
    });
    expect(result.stderr.toString()).toBe("");
    expect(result.exitCode).toBe(0);
  });
} else {
  let order: string[] = [];
  const stored = {
    type: "full" as const,
    age: randomInt(17, 100),
    joinReason: randomUUID(),
    aboutYou: randomUUID(),
    referralSource: randomUUID(),
  };
  const resolved = { uuid: randomUUID() };
  const getRetry = mock((): typeof stored | null => {
    order.push("retry");
    return stored;
  });
  const storeRetry = mock(() => {});
  const resolveUsername = mock(async () => {
    order.push("username");
    return resolved as typeof resolved | null;
  });
  const getLatestApplication = mock(async () => {
    order.push("load");
    return { status: "pending" };
  });
  const createApplication = mock(async () => { order.push("persist"); });
  const updateApplication = mock(async () => { order.push("save-edit"); });
  mock.module("../src/utils/config.js", () => ({ default: { LOG_CHANNEL_ID: randomUUID() } }));
  mock.module("../src/utils/logger.js", () => ({ default: { error: mock(), warn: mock() } }));
  mock.module("../src/utils/database.js", () => ({ default: { query: mock(async () => []) } }));
  mock.module("../src/utils/appDb.js", () => ({
    cancelPendingApplications: mock(async () => {}),
    getCurrentSeason: mock(async () => null),
    upsertUser: mock(async () => {}),
    createApplication,
    getLatestApplication,
    updateApplication,
  }));
  mock.module("../src/utils/retryStore.js", () => ({ getRetry, storeRetry }));
  mock.module("../src/utils/mojang.js", () => ({ resolveUsername }));
  mock.module("../src/utils/ticket.js", () => ({
    buildTicketLimitNotice: mock(), getCategoryMeta: mock(), getPlayerInfo: mock(),
  }));
  mock.module("../src/utils/ticketFlow.js", () => ({
    getLiveOpenTicketsForCategory: mock(), openTicket: mock(),
  }));
  const { MessageFlags } = await import("discord.js");
  const { default: ModalInteractionEvent } = await import("../src/events/ModalInteraction.js");
  const event = new ModalInteractionEvent();

  function interaction(customId = "application", age = String(stored.age)) {
    order = [];
    const fields: Record<string, string> = {
      age,
      "minecraft-username": `u${randomUUID().replaceAll("-", "").slice(0, 12)}`,
      "join-reason": randomUUID(),
      "about-you": randomUUID(),
    };
    const submittedMessage = { id: randomUUID() };
    const fetchLog = mock(async () => { order.push("log"); return null; });
    return {
      customId,
      isModalSubmit: () => true,
      isFromMessage: () => true,
      user: { id: randomUUID(), username: randomUUID() },
      fields: {
        getTextInputValue: (id: string) => fields[id],
        getStringSelectValues: () => [randomUUID()],
      },
      member: { guild: { channels: { fetch: fetchLog } } },
      deferReply: mock(async (_payload: unknown) => { order.push("defer"); }),
      reply: mock(async (_payload: unknown) => { order.push("reply"); }),
      followUp: mock(async (_payload: unknown) => { order.push("follow-up"); }),
      editReply: mock(async (_payload: unknown) => { order.push("edit-reply"); }),
      deleteReply: mock(async () => { order.push("delete-reply"); }),
      update: mock(async (_payload: unknown) => { order.push("update"); }),
      message: {
        delete: mock(async () => { order.push("delete-source"); }),
        edit: mock(async (_payload: unknown) => { order.push("edit-source"); }),
      },
      channel: {
        send: mock(async (_payload: unknown) => {
          order.push("send");
          await Promise.resolve();
          order.push("sent");
          return submittedMessage;
        }),
      },
      submittedMessage,
      fetchLog,
    };
  }

  function gate<T>() {
    let release!: (value: T) => void;
    const promise = new Promise<T>((resolve) => { release = resolve; });
    return { promise, release };
  }

  async function bounded<T>(promise: Promise<T>) {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        promise,
        new Promise<never>((_, reject) => {
          timeout = setTimeout(() => reject(new Error("Synthetic operation timed out")), 1_000);
        }),
      ]);
    } finally {
      clearTimeout(timeout);
    }
  }

  // A slow Discord fetch or username service must never use the initial
  // response window, and the welcome/retry message must survive that wait.
  for (const customId of ["application", "retry-application"]) {
    const input = interaction(customId);
    const started = gate<void>();
    const result = gate<void>();
    const waitForLookup = async <T>(value: T) => {
      order.push("lookup");
      started.release();
      await result.promise;
      return value;
    };
    if (customId === "application") input.fetchLog.mockImplementationOnce(() => waitForLookup(null));
    else resolveUsername.mockImplementationOnce(() => waitForLookup(resolved));
    const execution = event.execute(input as never);
    try {
      await bounded(started.promise);
      expect(order[0]).toBe("defer");
      expect(input.deferReply).toHaveBeenCalledWith({ flags: MessageFlags.Ephemeral });
      expect(input.message.delete).not.toHaveBeenCalled();
      expect(input.channel.send).not.toHaveBeenCalled();
    } finally {
      result.release();
      await bounded(execution);
    }
    expect(input.deferReply).toHaveBeenCalledTimes(1);
    expect(order.slice(-7)).toEqual(["persist", "send", "sent", "send", "sent", "delete-source", "delete-reply"]);
    expect(input.channel.send.mock.calls[1]?.[0]).toEqual(expect.objectContaining({
      reply: { messageReference: input.submittedMessage.id, failIfNotExists: false },
    }));
  }

  for (const customId of ["application", "retry-application", "edit-application"]) {
    const input = interaction(customId);
    const failure = new Error(`Synthetic expired interaction ${randomUUID()}`);
    input.deferReply.mockRejectedValueOnce(failure);
    getRetry.mockClear();
    getLatestApplication.mockClear();
    resolveUsername.mockClear();
    await expect(event.execute(input as never)).rejects.toBe(failure);
    expect(input.message.delete).not.toHaveBeenCalled();
    expect(input.fetchLog).not.toHaveBeenCalled();
    expect(getRetry).not.toHaveBeenCalled();
    expect(getLatestApplication).not.toHaveBeenCalled();
    expect(resolveUsername).not.toHaveBeenCalled();
  }

  // Both messages must finish successfully before removing the source.
  for (const failedSend of [1, 2]) {
    const input = interaction();
    let sends = 0;
    input.channel.send.mockImplementation(async () => {
      if (++sends === failedSend) throw new Error(`Synthetic send failure ${randomUUID()}`);
      return input.submittedMessage;
    });
    await event.execute(input as never);
    expect(sends).toBe(failedSend);
    expect(input.message.delete).not.toHaveBeenCalled();
    expect(input.deleteReply).not.toHaveBeenCalled();
    expect(JSON.stringify(input.editReply.mock.calls)).toMatch(/saved/i);
    expect(JSON.stringify(input.editReply.mock.calls)).toMatch(/moderator/i);
  }

  for (const customId of ["application", "retry-application"]) {
    const input = interaction(customId);
    resolveUsername.mockResolvedValueOnce(null);
    await event.execute(input as never);
    expect(order.slice(-3)).toEqual(["sent", "delete-source", "delete-reply"]);
    expect(input.channel.send).toHaveBeenCalledWith(expect.objectContaining({ flags: MessageFlags.IsComponentsV2 }));
    expect(input.followUp).not.toHaveBeenCalled();
    expect(input.reply).not.toHaveBeenCalled();
  }

  const failedRetryPrompt = interaction();
  resolveUsername.mockResolvedValueOnce(null);
  failedRetryPrompt.channel.send.mockRejectedValueOnce(new Error(`Synthetic reply failure ${randomUUID()}`));
  await expect(event.execute(failedRetryPrompt as never)).rejects.toThrow("Synthetic reply failure");
  expect(failedRetryPrompt.message.delete).not.toHaveBeenCalled();
  expect(failedRetryPrompt.deleteReply).not.toHaveBeenCalled();

  const underage = interaction("application", "16");
  await event.execute(underage as never);
  expect(underage.channel.send).toHaveBeenCalledWith(expect.objectContaining({ flags: MessageFlags.IsComponentsV2 }));
  expect(order.slice(-3)).toEqual(["sent", "delete-source", "delete-reply"]);

  const expired = interaction("retry-application");
  getRetry.mockReturnValueOnce(null);
  await event.execute(expired as never);
  expect(order).toEqual(["defer", "edit-reply"]);
  expect(JSON.stringify(expired.editReply.mock.calls)).toContain("expired");
  expect(expired.message.delete).not.toHaveBeenCalled();

  const edit = interaction("edit-application");
  const loadStarted = gate<void>();
  const loadResult = gate<{ status: string }>();
  getLatestApplication.mockImplementationOnce(async () => {
    order.push("load");
    loadStarted.release();
    return loadResult.promise;
  });
  const editing = event.execute(edit as never);
  try {
    await bounded(loadStarted.promise);
    expect(order).toEqual(["defer", "load"]);
    expect(edit.deferReply).toHaveBeenCalledWith({ flags: MessageFlags.Ephemeral });
    expect(edit.message.edit).not.toHaveBeenCalled();
  } finally {
    loadResult.release({ status: "pending" });
    await bounded(editing);
  }
  expect(order.slice(-3)).toEqual(["save-edit", "edit-source", "delete-reply"]);
  expect(edit.update).not.toHaveBeenCalled();
  expect(edit.reply).not.toHaveBeenCalled();
  expect(edit.message.delete).not.toHaveBeenCalled();
}
