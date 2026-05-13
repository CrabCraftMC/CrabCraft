import { type Message, type PartialMessage } from "discord.js";
import { extractNumberFromImage } from "./openai.js";

type MathFn = (...args: number[]) => number;

const MATH_CONSTANTS: Readonly<Record<string, number>> = Object.freeze({
  PI: Math.PI,
  TAU: Math.PI * 2,
  E: Math.E,
  PHI: (1 + Math.sqrt(5)) / 2,
});

const MATH_FUNCTIONS: Readonly<Record<string, MathFn>> = Object.freeze({
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
});

const MAX_EXPR_LENGTH = 200;
const INTEGER_TOLERANCE = 1e-9;

type Token =
  | { type: "num"; value: number }
  | { type: "id"; value: string }
  | { type: "op"; value: "+" | "-" | "*" | "/" | "**" }
  | { type: "lparen" }
  | { type: "rparen" }
  | { type: "comma" };

function isDigit(c: string): boolean {
  return c >= "0" && c <= "9";
}

function isIdStart(c: string): boolean {
  return (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") || c === "_";
}

function isIdCont(c: string): boolean {
  return isIdStart(c) || isDigit(c);
}

function tokenize(input: string): Token[] | null {
  const tokens: Token[] = [];
  let i = 0;
  while (i < input.length) {
    const c = input[i];
    if (c === " " || c === "\t" || c === "\n" || c === "\r") {
      i++;
      continue;
    }
    if (isDigit(c) || (c === "." && isDigit(input[i + 1] ?? ""))) {
      let j = i;
      while (j < input.length && isDigit(input[j])) j++;
      if (input[j] === ".") {
        j++;
        while (j < input.length && isDigit(input[j])) j++;
      }
      const value = parseFloat(input.slice(i, j));
      if (!Number.isFinite(value)) return null;
      tokens.push({ type: "num", value });
      i = j;
      continue;
    }
    if (isIdStart(c)) {
      let j = i;
      while (j < input.length && isIdCont(input[j])) j++;
      tokens.push({ type: "id", value: input.slice(i, j) });
      i = j;
      continue;
    }
    if (c === "*" && input[i + 1] === "*") {
      tokens.push({ type: "op", value: "**" });
      i += 2;
      continue;
    }
    if (c === "+" || c === "-" || c === "*" || c === "/") {
      tokens.push({ type: "op", value: c });
      i++;
      continue;
    }
    if (c === "(") {
      tokens.push({ type: "lparen" });
      i++;
      continue;
    }
    if (c === ")") {
      tokens.push({ type: "rparen" });
      i++;
      continue;
    }
    if (c === ",") {
      tokens.push({ type: "comma" });
      i++;
      continue;
    }
    return null;
  }
  return tokens;
}

type Node =
  | { type: "num"; value: number }
  | { type: "const"; name: string }
  | { type: "call"; name: string; args: Node[] }
  | { type: "unary"; op: "+" | "-"; arg: Node }
  | { type: "binary"; op: "+" | "-" | "*" | "/"; left: Node; right: Node }
  | { type: "pow"; base: Node; exp: Node };

class Parser {
  private pos = 0;
  constructor(private tokens: Token[]) {}

  private peek(): Token | undefined {
    return this.tokens[this.pos];
  }

  private consume(): Token | undefined {
    return this.tokens[this.pos++];
  }

  parse(): Node | null {
    const expr = this.parseAdditive();
    if (!expr) return null;
    if (this.peek()) return null;
    return expr;
  }

  private parseAdditive(): Node | null {
    let left = this.parseMultiplicative();
    if (!left) return null;
    for (;;) {
      const t = this.peek();
      if (t?.type !== "op" || (t.value !== "+" && t.value !== "-")) break;
      this.consume();
      const right = this.parseMultiplicative();
      if (!right) return null;
      left = { type: "binary", op: t.value, left, right };
    }
    return left;
  }

  private parseMultiplicative(): Node | null {
    let left = this.parseUnary();
    if (!left) return null;
    for (;;) {
      const t = this.peek();
      if (t?.type !== "op" || (t.value !== "*" && t.value !== "/")) break;
      this.consume();
      const right = this.parseUnary();
      if (!right) return null;
      left = { type: "binary", op: t.value, left, right };
    }
    return left;
  }

  private parseUnary(): Node | null {
    const t = this.peek();
    if (t?.type === "op" && (t.value === "+" || t.value === "-")) {
      this.consume();
      const arg = this.parseUnary();
      if (!arg) return null;
      return { type: "unary", op: t.value, arg };
    }
    return this.parsePower();
  }

  private parsePower(): Node | null {
    const base = this.parsePrimary();
    if (!base) return null;
    const t = this.peek();
    if (t?.type === "op" && t.value === "**") {
      this.consume();
      const exp = this.parseUnary();
      if (!exp) return null;
      return { type: "pow", base, exp };
    }
    return base;
  }

  private parsePrimary(): Node | null {
    const t = this.consume();
    if (!t) return null;
    if (t.type === "num") return { type: "num", value: t.value };
    if (t.type === "id") {
      if (this.peek()?.type === "lparen") {
        this.consume();
        const args: Node[] = [];
        if (this.peek()?.type !== "rparen") {
          const first = this.parseAdditive();
          if (!first) return null;
          args.push(first);
          while (this.peek()?.type === "comma") {
            this.consume();
            const arg = this.parseAdditive();
            if (!arg) return null;
            args.push(arg);
          }
        }
        if (this.consume()?.type !== "rparen") return null;
        return { type: "call", name: t.value, args };
      }
      return { type: "const", name: t.value };
    }
    if (t.type === "lparen") {
      const expr = this.parseAdditive();
      if (!expr) return null;
      if (this.consume()?.type !== "rparen") return null;
      return expr;
    }
    return null;
  }
}

function evaluate(node: Node): number | null {
  switch (node.type) {
    case "num":
      return node.value;
    case "const": {
      if (!Object.hasOwn(MATH_CONSTANTS, node.name)) return null;
      const v = MATH_CONSTANTS[node.name];
      return Number.isFinite(v) ? v : null;
    }
    case "call": {
      if (!Object.hasOwn(MATH_FUNCTIONS, node.name)) return null;
      const fn = MATH_FUNCTIONS[node.name];
      if (typeof fn !== "function") return null;
      const args: number[] = [];
      for (const a of node.args) {
        const v = evaluate(a);
        if (v === null) return null;
        args.push(v);
      }
      const r = fn(...args);
      return typeof r === "number" ? r : null;
    }
    case "unary": {
      const v = evaluate(node.arg);
      if (v === null) return null;
      return node.op === "-" ? -v : v;
    }
    case "binary": {
      const a = evaluate(node.left);
      if (a === null) return null;
      const b = evaluate(node.right);
      if (b === null) return null;
      switch (node.op) {
        case "+":
          return a + b;
        case "-":
          return a - b;
        case "*":
          return a * b;
        case "/":
          return a / b;
      }
    }
    // eslint-disable-next-line no-fallthrough
    case "pow": {
      const base = evaluate(node.base);
      if (base === null) return null;
      const exp = evaluate(node.exp);
      if (exp === null) return null;
      return Math.pow(base, exp);
    }
  }
}

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
  const normalized = raw
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

  if (!/[+\-*/]/.test(normalized) && !/[A-Za-z]/.test(normalized)) return null;

  const tokens = tokenize(normalized);
  if (!tokens || tokens.length === 0) return null;

  const ast = new Parser(tokens).parse();
  if (!ast) return null;

  const result = evaluate(ast);
  if (result === null || !Number.isFinite(result)) return null;

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
