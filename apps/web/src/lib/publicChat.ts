export const PUBLIC_CHAT_MESSAGE_LIMIT = 6;

export interface PublicChatMessage {
  id: string;
  timestamp: number;
  uuid: string;
  username: string;
  message: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isValidEpochMilliseconds(value: unknown): value is number {
  return (
    typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0 &&
    !Number.isNaN(new Date(value).getTime())
  );
}

export function parsePublicChatEvent(
  data: string,
  eventId: string,
): PublicChatMessage | null {
  const id = eventId.trim();
  if (!id) return null;

  let value: unknown;
  try {
    value = JSON.parse(data);
  } catch {
    return null;
  }

  if (
    !isRecord(value) ||
    !isValidEpochMilliseconds(value.timestamp) ||
    !isNonEmptyString(value.uuid) ||
    !isNonEmptyString(value.username) ||
    !isNonEmptyString(value.message)
  ) {
    return null;
  }

  return {
    id,
    timestamp: value.timestamp,
    uuid: value.uuid,
    username: value.username,
    message: value.message,
  };
}

export function appendPublicChatMessage(
  messages: PublicChatMessage[],
  message: PublicChatMessage,
): PublicChatMessage[] {
  if (messages.some((existing) => existing.id === message.id)) {
    return messages;
  }

  return [...messages, message].slice(-PUBLIC_CHAT_MESSAGE_LIMIT);
}
