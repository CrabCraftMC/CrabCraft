import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Renderer, type FontLoader } from "@takumi-rs/core";
import { container, image, text } from "@takumi-rs/helpers";
import { getSvgPath } from "figma-squircle";
import sharp from "sharp";

// Standalone announcement artwork. This does not post anything to Discord.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../..");
const publicDir = path.join(root, "apps/web/public");
const fontDir = path.join(root, "apps/bot/assets/player-card");
const output = path.join(root, "output/halloween-challenges-2026.png");
const fonts: FontLoader[] = [
  { name: "Unbounded", data: fs.readFileSync(path.join(fontDir, "Unbounded.ttf")) },
  { name: "Minecraft", data: fs.readFileSync(path.join(fontDir, "Minecraft.otf")) },
];
const CompatibleRenderer = Renderer as unknown as {
  new(options: { loadDefaultFonts: boolean; fonts: FontLoader[] }): Renderer;
};
const renderer = new CompatibleRenderer({ loadDefaultFonts: true, fonts });
const width = 1200;
const height = 1312;
const ink = "#f5f0eb";
const orange = "#fb923c";

function label(value: string, x: number, y: number, size: number, colour = ink, extra = {}) {
  return text(value, {
    position: "absolute", left: x, top: y, fontFamily: "Unbounded",
    fontSize: size, fontWeight: 500, color: colour,
    lineHeight: 1.55, whiteSpace: "pre-wrap", ...extra,
  } as any);
}
function panel(x: number, y: number, w: number, h: number, radius: number, background: string, children: any[], extra = {}) {
  const d = getSvgPath({ width: w, height: h, cornerRadius: radius, cornerSmoothing: 1 });
  const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><path d="${d}" fill="white"/></svg>`).toString("base64");
  return container({ style: {
    position: "absolute", left: x, top: y, width: w, height: h,
    maskImage: `url("data:image/svg+xml;base64,${mask}")`, maskSize: `${w}px ${h}px`,
    backgroundColor: background, ...extra,
  } as any, children });
}
async function sprite(relative: string, x: number, y: number, size: number) {
  const png = await sharp(path.resolve(publicDir, relative))
    .resize(size * 2, size * 2, { kernel: "nearest", fit: "contain", background: "#00000000" }).png().toBuffer();
  return image({ src: `data:image/png;base64,${png.toString("base64")}`, width: size, height: size,
    style: { position: "absolute", left: x, top: y } as any });
}
function web(x: number, y: number, flip = false) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="160" height="160" viewBox="0 0 160 160"><g fill="none" stroke="#7b5844" stroke-width="1.5" opacity=".48" ${flip ? 'transform="translate(160 0) scale(-1 1)"' : ''}><path d="M0 0L150 0M0 0L145 42M0 0L113 98M0 0L58 142M0 0L0 154"/><path d="M36 0Q30 6 34 10Q24 15 26 23Q12 25 13 33Q4 31 0 36M76 0Q64 12 71 21Q51 30 56 49Q25 49 29 70Q8 65 0 76M120 0Q103 19 115 33Q82 48 89 78Q40 81 46 112Q14 104 0 120"/></g></svg>`;
  return image({ src: `data:image/svg+xml;base64,${Buffer.from(svg).toString("base64")}`, width: 160, height: 160,
    style: { position: "absolute", left: x, top: y } as any });
}

const artwork = container({ style: { position: "relative", width, height, backgroundColor: "#171214" } as any, children: [
  web(0, 0), web(1040, 0, true),
  await sprite(path.join(root, "apps/bot/assets/halloween/crabcraft-ghost.png"), 47, 32, 62),
  label("CRABCRAFT", 128, 47, 24, ink, { fontWeight: 700, letterSpacing: 1.8 }),
  container({ style: {
    position: "absolute", left: 40, top: 266, width: 1120, height: 134,
    backgroundColor: "#271e1c", borderBottomLeftRadius: 36, borderBottomRightRadius: 36,
  } as any, children: [
    label("24 SEPTEMBER — 31 OCTOBER 2026", 25, 91, 17, ink, { fontWeight: 600, letterSpacing: 0.5 }),
  ] }),
  panel(40, 116, 1120, 222, 36, "#f97316", [
    container({ style: {
      position: "absolute", left: 40, top: 0, width: 520, height: 222,
      display: "flex", flexDirection: "column", alignItems: "flex-start", justifyContent: "center", gap: 8,
    } as any, children: [
      text("Halloween", {
        fontFamily: "Unbounded", fontSize: 65, fontWeight: 800, color: "#20130c",
        letterSpacing: -1.8, lineHeight: 1.2, textAlign: "left",
      } as any),
      text("Event", {
        fontFamily: "Minecraft", fontSize: 32, color: "#fff5e9",
        letterSpacing: 5, lineHeight: 1.2, textAlign: "left",
      } as any),
    ] }),
    container({ style: {
      position: "absolute", left: 560, top: 0, width: 560, height: 222,
      display: "flex", alignItems: "center", justifyContent: "center",
    } as any, children: [
      text("3 Challenges", {
        fontFamily: "Unbounded", fontSize: 58, fontWeight: 900,
        color: "rgba(77, 37, 17, 0.4)", lineHeight: 1,
        whiteSpace: "nowrap", letterSpacing: -1.5, textAlign: "center",
      } as any),
    ] }),
  ], { backgroundImage: "linear-gradient(120deg, #f97316, #fba347)" }),
  panel(40, 420, 1120, 222, 30, "#28201c", [
    label("01", 190, 25, 32, orange, { fontFamily: "Minecraft" }),
    await sprite("textures/blocks/pumpkin_face_off.png", 24, 39, 144),
    label("PUMPKIN HEAD’S HUNT", 250, 28, 27, ink, { fontWeight: 700 }),
    label("Wear a carved pumpkin and kill one of each:\nzombie, skeleton, spider, creeper and witch.\nComplete the hunt without dying.", 190, 88, 21, ink, { width: 860 }),
  ], { borderWidth: 1, borderColor: "#58402e" }),
  panel(40, 658, 1120, 190, 30, "#241e28", [
    label("02", 190, 25, 32, "#c4a6e6", { fontFamily: "Minecraft" }),
    await sprite("awards/icons/ring_bell.png", 24, 23, 144),
    label("TRICK OR TREAT", 250, 28, 27, ink, { fontWeight: 700 }),
    label("Place a pressure plate directly beside a bell.\nGet a creeper to step on it and ring the bell.", 190, 90, 21, ink, { width: 860 }),
  ], { borderWidth: 1, borderColor: "#493650" }),
  panel(40, 864, 1120, 190, 30, "#20251f", [
    label("03", 190, 25, 32, "#adca88", { fontFamily: "Minecraft" }),
    await sprite("advancements/icons/story_cure_zombie_villager.png", 24, 23, 144),
    label("BACK FROM THE DEAD", 250, 28, 27, ink, { fontWeight: 700 }),
    label("Cure a zombie villager. You must be wearing\na carved pumpkin when the cure finishes.", 190, 90, 21, ink, { width: 860 }),
  ], { borderWidth: 1, borderColor: "#3f4b34" }),
  panel(40, 1076, 1120, 116, 30, "#f97316", [
    label("Complete all the tasks above to receive an exclusive Discord role,", 28, 15, 20, "#21140c", { width: 1064, textAlign: "center", fontWeight: 600 }),
    container({ style: {
      position: "absolute", left: 28, top: 46, width: 1064, height: 31,
      display: "flex", flexDirection: "row", justifyContent: "center", alignItems: "center", gap: 7,
    } as any, children: [
      text("and have a", { fontFamily: "Unbounded", fontSize: 20, fontWeight: 600, color: "#21140c", lineHeight: 1.55 }),
      image({
        src: `data:image/png;base64,${(await sharp(path.join(root, "apps/bot/assets/halloween/reward-pumpkin.png")).resize(56, 56, { kernel: "nearest" }).png().toBuffer()).toString("base64")}`,
        width: 28, height: 28,
      }),
      text("pumpkin next to your name in-game.", { fontFamily: "Unbounded", fontSize: 20, fontWeight: 600, color: "#21140c", lineHeight: 1.55 }),
    ] }),
    label("The in-game tag lasts while the event is active.", 28, 80, 16, "#21140c", { width: 1064, textAlign: "center", fontWeight: 500 }),
  ], { backgroundImage: "linear-gradient(120deg, #f97316, #fb923c)" }),
  label("crabcraft.net", 50, 1222, 36, orange, { fontFamily: "Minecraft" }),
  label("/halloween", 730, 1222, 36, orange, { fontFamily: "Minecraft", width: 420, textAlign: "right" }),
] });
const options: NonNullable<Parameters<Renderer["render"]>[1]> & { fonts: FontLoader[] } = {
  format: "png", devicePixelRatio: 2, fonts,
};
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, Buffer.from(await renderer.render(artwork, options)));
console.log(output);
