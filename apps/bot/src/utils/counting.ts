import { type Message, type PartialMessage } from "discord.js";
import { extractNumberFromImage } from "./openai.js";

export function parseNumberFromText(content: string): number | null {
  const trimmed = content.trim();
  if (!trimmed) return null;

  const math = parseMathExpression(trimmed);
  if (math !== null) return math;

  const numMatch = trimmed.match(/^(\d+)(?:$|[\s!?.,])/);
  if (!numMatch) return null;
  const n = parseInt(numMatch[1], 10);
  return Number.isFinite(n) ? n : null;
}

function parseMathExpression(text: string): number | null {
  const exprMatch = text.match(/^[\d+\-*/xX()\s]+/);
  if (!exprMatch) return null;

  const raw = exprMatch[0].trimEnd();
  if (!raw) return null;
  if (!/[+\-*/xX]/.test(raw)) return null;

  const expr = raw.replace(/[xX]/g, "*");
  if (!/^[\d+\-*/()\s]+$/.test(expr)) return null;

  let result: unknown;
  try {
    result = new Function(`"use strict"; return (${expr});`)();
  } catch {
    return null;
  }

  if (typeof result !== "number" || !Number.isFinite(result)) return null;
  if (!Number.isInteger(result) || result < 0) return null;
  return result;
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
