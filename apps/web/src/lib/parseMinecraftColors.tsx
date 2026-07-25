import React from "react";

interface ColorSegment {
  text: string;
  color?: string;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
  strikethrough?: boolean;
  obfuscated?: boolean;
}

const STANDARD_COLORS: Record<string, string> = {
  "0": "#000000",
  "1": "#0000AA",
  "2": "#00AA00",
  "3": "#00AAAA",
  "4": "#AA0000",
  "5": "#AA00AA",
  "6": "#FFAA00",
  "7": "#AAAAAA",
  "8": "#555555",
  "9": "#5555FF",
  a: "#55FF55",
  b: "#55FFFF",
  c: "#FF5555",
  d: "#FF55FF",
  e: "#FFFF55",
  f: "#FFFFFF",
};

export function parseMinecraftColors(raw: string): ColorSegment[] {
  const segments: ColorSegment[] = [];
  let currentColor: string | undefined;
  let bold = false;
  let italic = false;
  let underline = false;
  let strikethrough = false;
  let obfuscated = false;
  let currentText = "";

  const pushSegment = () => {
    if (currentText) {
      segments.push({
        text: currentText,
        color: currentColor,
        bold: bold || undefined,
        italic: italic || undefined,
        underline: underline || undefined,
        strikethrough: strikethrough || undefined,
        obfuscated: obfuscated || undefined,
      });
      currentText = "";
    }
  };

  let i = 0;
  while (i < raw.length) {
    if (raw[i] === "\u00a7" && i + 1 < raw.length) {
      const code = raw[i + 1].toLowerCase();

      if (code === "x" && i + 13 < raw.length) {
        // Hex color: §x§R§R§G§G§B§B
        let hex = "#";
        let valid = true;
        for (let j = 0; j < 6; j++) {
          const sectionIdx = i + 2 + j * 2;
          if (raw[sectionIdx] === "\u00a7") {
            hex += raw[sectionIdx + 1];
          } else {
            valid = false;
            break;
          }
        }
        if (valid) {
          pushSegment();
          currentColor = hex.toUpperCase();
          i += 14; // skip §x + 6×(§digit)
          continue;
        }
      }

      if (STANDARD_COLORS[code]) {
        pushSegment();
        currentColor = STANDARD_COLORS[code];
        i += 2;
        continue;
      }

      switch (code) {
        case "k":
          pushSegment();
          obfuscated = true;
          i += 2;
          continue;
        case "l":
          pushSegment();
          bold = true;
          i += 2;
          continue;
        case "m":
          pushSegment();
          strikethrough = true;
          i += 2;
          continue;
        case "n":
          pushSegment();
          underline = true;
          i += 2;
          continue;
        case "o":
          pushSegment();
          italic = true;
          i += 2;
          continue;
        case "r":
          pushSegment();
          currentColor = undefined;
          bold = false;
          italic = false;
          underline = false;
          strikethrough = false;
          obfuscated = false;
          i += 2;
          continue;
      }

      // Unknown code — skip
      i += 2;
      continue;
    }

    currentText += raw[i];
    i++;
  }

  pushSegment();
  return segments;
}

// sRGB → linear
const lin = (c: number) => {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
};

function luminance(hex: string): number {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrastRatio(l1: number, l2: number): number {
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Blend a color toward white until it reaches the target contrast against
 * a colored background — used for nicknames on the orange profile header,
 * where similar hues (gold, orange) disappear entirely. Blending toward
 * white (rather than black) keeps bright nicks bright; the header's dark
 * text shadow handles whatever contrast this can't reach.
 */
function ensureContrastOn(hex: string, bgHex: string, target = 2): string {
  const bgL = luminance(bgHex);
  if (contrastRatio(luminance(hex), bgL) >= target) return hex;

  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 8; i++) {
    const t = (lo + hi) / 2;
    const mixed =
      0.2126 * lin(r + (255 - r) * t) +
      0.7152 * lin(g + (255 - g) * t) +
      0.0722 * lin(b + (255 - b) * t);
    if (contrastRatio(mixed, bgL) >= target) hi = t;
    else lo = t;
  }
  const mix = (c: number) => Math.round(c + (255 - c) * hi);
  return (
    "#" +
    [mix(r), mix(g), mix(b)].map((c) => c.toString(16).padStart(2, "0")).join("")
  );
}

/** Darken colors that lack contrast against white (WCAG ~3:1). */
function ensureReadable(hex: string): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);

  const L = luminance(hex);

  // Contrast ratio against white (L=1): (1.05) / (L + 0.05)
  const contrast = 1.05 / (L + 0.05);
  if (contrast >= 3) return hex;

  // Scale RGB down to hit target luminance ≈ 0.3 (gives ~3:1 vs white)
  const scale = 0.55;
  const clamp = (v: number) =>
    Math.min(255, Math.max(0, Math.round(v * scale)));
  return (
    "#" +
    [clamp(r), clamp(g), clamp(b)]
      .map((c) => c.toString(16).padStart(2, "0"))
      .join("")
  );
}

export function ColoredNickname({
  raw,
  exact,
  contrastBg,
  shadow,
}: {
  raw: string;
  exact?: boolean;
  /** Lighten segments that would blend into this background color. */
  contrastBg?: string;
  /** Minecraft-style dark drop shadow behind each glyph. */
  shadow?: boolean;
}) {
  const segments = parseMinecraftColors(raw);

  return (
    <span className="inline-flex">
      {segments.map((seg, i) => {
        const style: React.CSSProperties = {};
        if (seg.color) {
          style.color = contrastBg
            ? ensureContrastOn(seg.color, contrastBg)
            : exact
              ? seg.color
              : ensureReadable(seg.color);
        }
        if (shadow) style.textShadow = "0.07em 0.07em 0 rgba(0,0,0,0.45)";
        if (seg.bold) style.fontWeight = "bold";
        if (seg.italic) style.fontStyle = "italic";

        const decorations: string[] = [];
        if (seg.underline) decorations.push("underline");
        if (seg.strikethrough) decorations.push("line-through");
        if (decorations.length) style.textDecoration = decorations.join(" ");

        return (
          <span key={i} style={style}>
            {seg.text}
          </span>
        );
      })}
    </span>
  );
}
