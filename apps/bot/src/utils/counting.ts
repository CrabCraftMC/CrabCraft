import { type Message, type PartialMessage } from "discord.js";
import { extractNumberFromImage } from "./openai.js";

const MATH_SCOPE: Record<string, number | ((...args: number[]) => number)> = {
  sqrt: Math.sqrt,
  cbrt: Math.cbrt,
  abs: Math.abs,
  floor: Math.floor,
  ceil: Math.ceil,
  round: Math.round,
  pow: Math.pow,
  min: Math.min,
  max: Math.max,
  log: Math.log,
  log2: Math.log2,
  log10: Math.log10,
  exp: Math.exp,
  PI: Math.PI,
  TAU: Math.PI * 2,
  E: Math.E,
  PHI: (1 + Math.sqrt(5)) / 2,
};
const ALLOWED_IDENTIFIERS = new Set(Object.keys(MATH_SCOPE));
const MAX_EXPR_LENGTH = 200;
const INTEGER_TOLERANCE = 1e-9;

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
  const exprMatch = text.match(/^[\d+\-*/().,\s^_a-zA-Zπτφ√]+/u);
  if (!exprMatch) return null;
  if (exprMatch[0].length > MAX_EXPR_LENGTH) return null;

  let chunk = exprMatch[0].trimEnd();
  while (chunk) {
    const result = tryEvalMath(chunk);
    if (result !== null) return result;
    const cut = chunk.search(/\s+\S*$/);
    if (cut === -1) return null;
    chunk = chunk.slice(0, cut);
  }
  return null;
}

function tryEvalMath(raw: string): number | null {
  const expr = raw
    .replace(/π/g, "PI")
    .replace(/τ/g, "TAU")
    .replace(/φ/g, "PHI")
    .replace(/√/g, "sqrt")
    .replace(/\bpi\b/g, "PI")
    .replace(/\btau\b/g, "TAU")
    .replace(/\bphi\b/g, "PHI")
    .replace(/\be\b/g, "E")
    .replace(/(?<![A-Za-z_])[xX](?![A-Za-z_])/g, "*")
    .replace(/\^/g, "**");

  if (!/[+\-*/]/.test(expr) && !/[A-Za-z]/.test(expr)) return null;

  const identifiers = expr.match(/[A-Za-z_][A-Za-z_0-9]*/g) ?? [];
  for (const id of identifiers) {
    if (!ALLOWED_IDENTIFIERS.has(id)) return null;
  }

  if (!/^[\d+\-*/().,\s_A-Za-z]+$/.test(expr)) return null;

  const names = Object.keys(MATH_SCOPE);
  const values = Object.values(MATH_SCOPE);
  let result: unknown;
  try {
    const fn = new Function(...names, `"use strict"; return (${expr});`);
    result = fn(...values);
  } catch {
    return null;
  }

  if (typeof result !== "number" || !Number.isFinite(result)) return null;
  const rounded = Math.round(result);
  if (Math.abs(result - rounded) > INTEGER_TOLERANCE) return null;
  if (rounded < 0 || rounded > Number.MAX_SAFE_INTEGER) return null;
  return rounded;
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
