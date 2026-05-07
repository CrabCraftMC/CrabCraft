import { type Message, type PartialMessage } from "discord.js";
import { extractNumberFromImage } from "./openai.js";

export function parseNumberFromText(content: string): number | null {
  const trimmed = content.trim();
  if (!trimmed) return null;
  const match = trimmed.match(/^(\d+)(?:$|[\s!?.,])/);
  if (!match) return null;
  const n = parseInt(match[1], 10);
  return Number.isFinite(n) ? n : null;
}

function findImageUrl(message: Message | PartialMessage): string | null {
  const att = message.attachments?.find((a) => {
    if (a.contentType?.startsWith("image/")) return true;
    return /\.(png|jpe?g|gif|webp)$/i.test(a.url);
  });
  if (att) return att.url;

  for (const embed of message.embeds ?? []) {
    const url = embed.image?.url ?? embed.thumbnail?.url;
    if (url) return url;
  }
  return null;
}

export async function parseNumberFromMessage(
  message: Message | PartialMessage,
): Promise<number | null> {
  const text = parseNumberFromText(message.content ?? "");
  if (text !== null) return text;

  const url = findImageUrl(message);
  if (!url) return null;
  return await extractNumberFromImage(url);
}
