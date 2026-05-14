import { type Message, type PartialMessage } from "discord.js";
import { extractNumberFromImage, transcribeAudio } from "./openai.js";

const VOICE_MAX_DURATION_SECONDS = 10;

const SPOKEN_SMALL: Readonly<Record<string, number>> = Object.freeze({
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5,
  six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15,
  sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19,
  twenty: 20, thirty: 30, forty: 40, fifty: 50,
  sixty: 60, seventy: 70, eighty: 80, ninety: 90,
});
const SPOKEN_MULT: Readonly<Record<string, number>> = Object.freeze({
  hundred: 100,
  thousand: 1000,
  million: 1_000_000,
  billion: 1_000_000_000,
});

function parseSpokenNumber(text: string): number | null {
  const words = text
    .toLowerCase()
    .replace(/[^a-z\s\-]/g, " ")
    .split(/[\s\-]+/)
    .filter((w) => w && w !== "and");
  if (words.length === 0) return null;

  for (const w of words) {
    if (!Object.hasOwn(SPOKEN_SMALL, w) && !Object.hasOwn(SPOKEN_MULT, w)) {
      return null;
    }
  }

  let total = 0;
  let current = 0;
  for (const w of words) {
    if (Object.hasOwn(SPOKEN_SMALL, w)) {
      current += SPOKEN_SMALL[w];
    } else {
      const m = SPOKEN_MULT[w];
      if (m === 100) {
        current = Math.max(current, 1) * 100;
      } else {
        total += Math.max(current, 1) * m;
        current = 0;
      }
    }
  }
  total += current;
  return total > 0 || words[0] === "zero" ? total : null;
}

type Complex = { re: number; im: number };
type MathFn = (...args: Complex[]) => Complex;

const NAN_COMPLEX: Complex = { re: NaN, im: NaN };

function cReal(n: number): Complex {
  return { re: n, im: 0 };
}

function cAdd(a: Complex, b: Complex): Complex {
  return { re: a.re + b.re, im: a.im + b.im };
}

function cSub(a: Complex, b: Complex): Complex {
  return { re: a.re - b.re, im: a.im - b.im };
}

function cMul(a: Complex, b: Complex): Complex {
  return {
    re: a.re * b.re - a.im * b.im,
    im: a.re * b.im + a.im * b.re,
  };
}

function cDiv(a: Complex, b: Complex): Complex {
  const d = b.re * b.re + b.im * b.im;
  if (d === 0) return NAN_COMPLEX;
  return {
    re: (a.re * b.re + a.im * b.im) / d,
    im: (a.im * b.re - a.re * b.im) / d,
  };
}

function cNeg(a: Complex): Complex {
  return { re: -a.re, im: -a.im };
}

function cAbs(a: Complex): number {
  return Math.hypot(a.re, a.im);
}

function cExp(a: Complex): Complex {
  const r = Math.exp(a.re);
  return { re: r * Math.cos(a.im), im: r * Math.sin(a.im) };
}

function cLog(a: Complex): Complex {
  const r = cAbs(a);
  if (r === 0) return NAN_COMPLEX;
  return { re: Math.log(r), im: Math.atan2(a.im, a.re) };
}

function cPow(base: Complex, exp: Complex): Complex {
  if (base.re === 0 && base.im === 0) {
    if (exp.im === 0 && exp.re > 0) return cReal(0);
    if (exp.re === 0 && exp.im === 0) return cReal(1);
    return NAN_COMPLEX;
  }
  if (base.im === 0 && exp.im === 0 && (base.re > 0 || Number.isInteger(exp.re))) {
    return cReal(Math.pow(base.re, exp.re));
  }
  return cExp(cMul(exp, cLog(base)));
}

function cSqrt(a: Complex): Complex {
  if (a.im === 0) {
    if (a.re >= 0) return cReal(Math.sqrt(a.re));
    return { re: 0, im: Math.sqrt(-a.re) };
  }
  const r = cAbs(a);
  return {
    re: Math.sqrt((r + a.re) / 2),
    im: Math.sign(a.im) * Math.sqrt((r - a.re) / 2),
  };
}

function cCbrt(a: Complex): Complex {
  if (a.im === 0) return cReal(Math.cbrt(a.re));
  return cPow(a, cReal(1 / 3));
}

const IM_TOLERANCE = 1e-9;
const isReal = (a: Complex): boolean => Math.abs(a.im) < IM_TOLERANCE;

const MATH_CONSTANTS: Readonly<Record<string, Complex>> = Object.freeze({
  PI: cReal(Math.PI),
  TAU: cReal(Math.PI * 2),
  E: cReal(Math.E),
  PHI: cReal((1 + Math.sqrt(5)) / 2),
  i: { re: 0, im: 1 },
  I: { re: 0, im: 1 },
});

const MATH_FUNCTIONS: Readonly<Record<string, MathFn>> = Object.freeze({
  sqrt: (a) => cSqrt(a),
  cbrt: (a) => cCbrt(a),
  abs: (a) => cReal(cAbs(a)),
  floor: (a) => (isReal(a) ? cReal(Math.floor(a.re)) : NAN_COMPLEX),
  ceil: (a) => (isReal(a) ? cReal(Math.ceil(a.re)) : NAN_COMPLEX),
  round: (a) => (isReal(a) ? cReal(Math.round(a.re)) : NAN_COMPLEX),
  pow: (a, b) => cPow(a, b),
  min: (...args) => {
    if (args.some((a) => !isReal(a))) return NAN_COMPLEX;
    return cReal(Math.min(...args.map((a) => a.re)));
  },
  max: (...args) => {
    if (args.some((a) => !isReal(a))) return NAN_COMPLEX;
    return cReal(Math.max(...args.map((a) => a.re)));
  },
  log: (a) => cLog(a),
  log2: (a) => cDiv(cLog(a), cReal(Math.LN2)),
  log10: (a) => cDiv(cLog(a), cReal(Math.LN10)),
  exp: (a) => cExp(a),
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

function evaluate(node: Node): Complex | null {
  switch (node.type) {
    case "num":
      return cReal(node.value);
    case "const": {
      if (!Object.hasOwn(MATH_CONSTANTS, node.name)) return null;
      const v = MATH_CONSTANTS[node.name];
      if (!v || !Number.isFinite(v.re) || !Number.isFinite(v.im)) return null;
      return v;
    }
    case "call": {
      if (!Object.hasOwn(MATH_FUNCTIONS, node.name)) return null;
      const fn = MATH_FUNCTIONS[node.name];
      if (typeof fn !== "function") return null;
      const args: Complex[] = [];
      for (const a of node.args) {
        const v = evaluate(a);
        if (v === null) return null;
        args.push(v);
      }
      const r = fn(...args);
      if (!r || typeof r.re !== "number" || typeof r.im !== "number") return null;
      return r;
    }
    case "unary": {
      const v = evaluate(node.arg);
      if (v === null) return null;
      return node.op === "-" ? cNeg(v) : v;
    }
    case "binary": {
      const a = evaluate(node.left);
      if (a === null) return null;
      const b = evaluate(node.right);
      if (b === null) return null;
      switch (node.op) {
        case "+":
          return cAdd(a, b);
        case "-":
          return cSub(a, b);
        case "*":
          return cMul(a, b);
        case "/":
          return cDiv(a, b);
      }
    }
    // eslint-disable-next-line no-fallthrough
    case "pow": {
      const base = evaluate(node.base);
      if (base === null) return null;
      const exp = evaluate(node.exp);
      if (exp === null) return null;
      return cPow(base, exp);
    }
  }
}

const DIGIT_BASES: readonly number[] = Object.freeze([
  0x30, 0x660, 0x6f0, 0x7c0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6,
  0xc66, 0xce6, 0xd66, 0xde6, 0xe50, 0xed0, 0xf20, 0x1040, 0x1090, 0x17e0,
  0x1810, 0x1946, 0x19d0, 0x1a80, 0x1a90, 0x1b50, 0x1bb0, 0x1c40, 0x1c50,
  0xa620, 0xa8d0, 0xa900, 0xa9d0, 0xa9f0, 0xaa50, 0xabf0, 0xff10,
  0x104a0, 0x10d30, 0x11066, 0x110f0, 0x11136, 0x111d0, 0x112f0, 0x11450,
  0x114d0, 0x11650, 0x116c0, 0x11730, 0x118e0, 0x11c50, 0x11d50, 0x11da0,
  0x11f50, 0x16a60, 0x16ac0, 0x16b50,
  0x1d7ce, 0x1d7d8, 0x1d7e2, 0x1d7ec, 0x1d7f6,
  0x1e140, 0x1e2f0, 0x1e4f0, 0x1e950, 0x1fbf0,
]);

function digitValueOf(cp: number): number {
  for (const base of DIGIT_BASES) {
    if (cp >= base && cp < base + 10) return cp - base;
  }
  return -1;
}

const SHORTCODE_DIGITS: Readonly<Record<string, string>> = Object.freeze({
  ":zero:": "0",
  ":one:": "1",
  ":two:": "2",
  ":three:": "3",
  ":four:": "4",
  ":five:": "5",
  ":six:": "6",
  ":seven:": "7",
  ":eight:": "8",
  ":nine:": "9",
  ":keycap_ten:": "10",
});

function normalizeInputDigits(text: string): string {
  let s = text.replace(/\u{1F51F}/gu, "10");
  s = s.replace(/\u{FE0F}\u{20E3}/gu, "");
  for (const [code, digit] of Object.entries(SHORTCODE_DIGITS)) {
    if (s.includes(code)) s = s.split(code).join(digit);
  }
  let out = "";
  for (const c of s) {
    const cp = c.codePointAt(0);
    if (cp === undefined) continue;
    const v = digitValueOf(cp);
    out += v >= 0 ? String(v) : c;
  }
  return out;
}

const ROMAN_VALUES: Readonly<Record<string, number>> = Object.freeze({
  M: 1000, D: 500, C: 100, L: 50, X: 10, V: 5, I: 1,
});

function toRoman(n: number): string {
  const pairs: ReadonlyArray<readonly [number, string]> = [
    [1000, "M"], [900, "CM"], [500, "D"], [400, "CD"],
    [100, "C"], [90, "XC"], [50, "L"], [40, "XL"],
    [10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"],
  ];
  let s = "";
  for (const [v, sym] of pairs) {
    while (n >= v) {
      s += sym;
      n -= v;
    }
  }
  return s;
}

function parseRoman(text: string): number | null {
  const m = text.toUpperCase().match(/^([MDCLXVI]+)(?:$|[\s!?.,])/);
  if (!m) return null;
  const roman = m[1];
  if (roman.length > 16) return null;
  let total = 0;
  let prev = 0;
  for (let i = roman.length - 1; i >= 0; i--) {
    const v = ROMAN_VALUES[roman[i]];
    if (v < prev) total -= v;
    else total += v;
    prev = v;
  }
  if (total <= 0 || total > 3999) return null;
  if (toRoman(total) !== roman) return null;
  return total;
}

function parseTally(text: string): number | null {
  let count = 0;
  let hasTally = false;
  for (const c of text) {
    if (c === "|" || c === "\u{1D377}") {
      count += 1;
      hasTally = true;
    } else if (c === "\u{1D378}") {
      count += 5;
      hasTally = true;
    } else if (c === " " || c === "\t" || c === "\n" || c === "\r") {
      continue;
    } else {
      return null;
    }
  }
  if (!hasTally || count < 2) return null;
  return count;
}

export function parseNumberFromText(content: string): number | null {
  const trimmed = normalizeInputDigits(content.trim());
  if (!trimmed) return null;

  const math = parseMathExpression(trimmed);
  if (math !== null) return math;

  const roman = parseRoman(trimmed);
  if (roman !== null) return roman;

  const tally = parseTally(trimmed);
  if (tally !== null) return tally;

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
    .replace(/\b0[xX]([0-9a-fA-F]+)\b/g, (_, h) => {
      const n = parseInt(h, 16);
      return Number.isFinite(n) ? n.toString() : "NaN";
    })
    .replace(/\b0[bB]([01]+)\b/g, (_, b) => {
      const n = parseInt(b, 2);
      return Number.isFinite(n) ? n.toString() : "NaN";
    })
    .replace(/\b0[oO]([0-7]+)\b/g, (_, o) => {
      const n = parseInt(o, 8);
      return Number.isFinite(n) ? n.toString() : "NaN";
    })
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

  const tokens = tokenize(normalized);
  if (!tokens || tokens.length === 0) return null;

  const ast = new Parser(tokens).parse();
  if (!ast) return null;

  const result = evaluate(ast);
  if (
    result === null ||
    !Number.isFinite(result.re) ||
    !Number.isFinite(result.im)
  ) {
    return null;
  }
  if (Math.abs(result.im) > IM_TOLERANCE) return null;

  const rounded = Math.round(result.re);
  if (Math.abs(result.re - rounded) > INTEGER_TOLERANCE) return null;
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

function findVoiceUrl(message: Message | PartialMessage): string | null {
  const att = message.attachments?.find((a) => {
    if (!a.contentType?.startsWith("audio/")) return false;
    const duration = (a as { duration?: number }).duration;
    if (typeof duration !== "number" || !Number.isFinite(duration)) return false;
    return duration > 0 && duration <= VOICE_MAX_DURATION_SECONDS;
  });
  return att?.url ?? null;
}

async function parseNumberFromVoice(
  message: Message | PartialMessage,
): Promise<number | null> {
  const url = findVoiceUrl(message);
  if (!url) return null;
  const transcript = await transcribeAudio(url);
  if (!transcript) return null;
  const direct = parseNumberFromText(transcript);
  if (direct !== null) return direct;
  return parseSpokenNumber(transcript);
}

export async function parseNumberFromMessage(
  message: Message | PartialMessage,
): Promise<number | null> {
  const text = parseNumberFromText(message.content ?? "");
  if (text !== null) return text;

  const voice = await parseNumberFromVoice(message);
  if (voice !== null) return voice;

  const url = findImageUrl(message);
  if (!url) return null;
  return await extractNumberFromImage(url);
}
