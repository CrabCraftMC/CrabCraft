import { describe, expect, test } from "bun:test";
import {
  appendPublicChatMessage,
  parsePublicChatEvent,
  PUBLIC_CHAT_MESSAGE_LIMIT,
  type PublicChatMessage,
} from "../src/lib/publicChat";

const BASE_MESSAGE = {
  timestamp: 1_786_292_400_000,
  uuid: "1b63f314-5ee7-4c23-9f7e-5c5c502d7d32",
  username: "CrabPlayer",
  message: "Hello from the server!",
};

function makeMessage(id: string): PublicChatMessage {
  return { id, ...BASE_MESSAGE };
}

describe("parsePublicChatEvent", () => {
  test("parses a valid message and uses the SSE event ID", () => {
    expect(parsePublicChatEvent(JSON.stringify(BASE_MESSAGE), "1786292400000-0"))
      .toEqual({
        id: "1786292400000-0",
        ...BASE_MESSAGE,
      });
  });

  test("rejects malformed or incomplete events", () => {
    expect(parsePublicChatEvent("not json", "1786292400000-0")).toBeNull();
    expect(parsePublicChatEvent(JSON.stringify(BASE_MESSAGE), " ")).toBeNull();
    expect(
      parsePublicChatEvent(
        JSON.stringify({ ...BASE_MESSAGE, timestamp: "1786292400000" }),
        "1786292400000-0",
      ),
    ).toBeNull();
    expect(
      parsePublicChatEvent(
        JSON.stringify({ ...BASE_MESSAGE, message: " " }),
        "1786292400000-0",
      ),
    ).toBeNull();
    expect(
      parsePublicChatEvent(
        JSON.stringify({ ...BASE_MESSAGE, username: null }),
        "1786292400000-0",
      ),
    ).toBeNull();
  });
});

describe("appendPublicChatMessage", () => {
  test("ignores duplicate event IDs", () => {
    const messages = [makeMessage("1-0")];

    expect(appendPublicChatMessage(messages, makeMessage("1-0"))).toBe(messages);
  });

  test("keeps the newest messages in arrival order", () => {
    const messages = Array.from(
      { length: PUBLIC_CHAT_MESSAGE_LIMIT + 2 },
      (_, index) => makeMessage(`${index + 1}-0`),
    ).reduce(appendPublicChatMessage, [] as PublicChatMessage[]);

    expect(messages.map((message) => message.id)).toEqual([
      "3-0",
      "4-0",
      "5-0",
      "6-0",
      "7-0",
      "8-0",
    ]);
  });
});
