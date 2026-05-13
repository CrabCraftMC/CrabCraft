import { type Message, type PartialMessage } from "discord.js";
import { extractNumberFromImage } from "./openai.js";

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

export async function parseNumberFromMessage(
  message: Message | PartialMessage,
): Promise<number | null> {
  const text = parseNumberFromText(message.content ?? "");
  if (text !== null) return text;

  const url = findImageUrl(message);
  if (!url) return null;
  return await extractNumberFromImage(url);
}
