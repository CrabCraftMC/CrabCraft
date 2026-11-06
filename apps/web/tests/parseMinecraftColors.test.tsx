import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { ColoredNickname, parseMinecraftColors } from "../src/lib/parseMinecraftColors";

const gradientNickname = "&#faf216&l&oD&#f7f72f&l&ou&#e4ef32&l&oc&#c3da1e&l&ok";

describe("parseMinecraftColors", () => {
  test("parses ampersand hex gradients and decorations", () => {
    const segments = parseMinecraftColors(gradientNickname);
    expect(segments.map(({ text, color, bold, italic }) => ({ text, color, bold, italic }))).toEqual([
      { text: "D", color: "#FAF216", bold: true, italic: true },
      { text: "u", color: "#F7F72F", bold: true, italic: true },
      { text: "c", color: "#E4EF32", bold: true, italic: true },
      { text: "k", color: "#C3DA1E", bold: true, italic: true },
    ]);
  });

  test.each(["&", "§"])("parses %s legacy colours, decorations and resets", (marker) => {
    const segments = parseMinecraftColors(`${marker}A${marker}L${marker}O${marker}N${marker}M${marker}KCrab${marker}rCraft`);
    expect(segments).toEqual([
      { text: "Crab", color: "#55FF55", bold: true, italic: true, underline: true, strikethrough: true, obfuscated: true },
      { text: "Craft", color: undefined, bold: undefined, italic: undefined, underline: undefined, strikethrough: undefined, obfuscated: undefined },
    ]);
  });

  test.each(["&", "§"])("parses %s repeated hex colours", (marker) => {
    const [segment] = parseMinecraftColors(`${marker}X${marker}f${marker}A${marker}F${marker}2${marker}1${marker}6Crab`);
    expect(segment.text).toBe("Crab");
    expect(segment.color).toBe("#FAF216");
  });

  test.each(["&#12Ab34", "&a", "&x&1&2&A&b&3&4", "§a", "§x§1§2§A§b§3§4"])("%s resets decorations on colour changes", (colour) => {
    const [segment] = parseMinecraftColors(`&l&o&n&m&k${colour}Crab`);
    expect(segment.text).toBe("Crab");
    for (const decoration of [segment.bold, segment.italic, segment.underline, segment.strikethrough, segment.obfuscated]) {
      expect(decoration).toBeUndefined();
    }
  });

  test.each(["Crab & Craft", "Crab&", "&qCrab", "&#12345", "&#zzzzzzCrab", "&x&z&z&z&z&z&zCrab"])("preserves literal text: %s", (raw) => {
    expect(parseMinecraftColors(raw).map(({ text }) => text).join("")).toBe(raw);
    expect(parseMinecraftColors(raw).every(({ color }) => color === undefined)).toBe(true);
  });

  test("does not accept non-hex digits in section hex colours", () => {
    expect(parseMinecraftColors("§x§z§z§z§z§z§zCrab").every(({ color }) => color === undefined)).toBe(true);
  });
});

describe("ColoredNickname", () => {
  test("renders exact gradient colours and styles without format codes", () => {
    const html = renderToStaticMarkup(<ColoredNickname raw={gradientNickname} exact />);
    expect(html.replace(/<[^>]*>/g, "")).toBe("Duck");
    for (const [colour, letter] of [["#FAF216", "D"], ["#F7F72F", "u"], ["#E4EF32", "c"], ["#C3DA1E", "k"]]) {
      expect(html).toContain(`style="color:${colour};font-weight:bold;font-style:italic">${letter}</span>`);
    }
  });
});
