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

/** Darken colors that lack contrast against white (WCAG ~3:1). */
function ensureReadable(hex: string): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);

  // sRGB → linear
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  const L = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);

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

export function ColoredNickname({ raw, exact }: { raw: string; exact?: boolean }) {
  const segments = parseMinecraftColors(raw);

  return (
    <span className="inline-flex">
      {segments.map((seg, i) => {
        const style: React.CSSProperties = {};
        if (seg.color) style.color = exact ? seg.color : ensureReadable(seg.color);
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
