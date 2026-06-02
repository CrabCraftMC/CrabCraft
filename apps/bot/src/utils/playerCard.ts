import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { Renderer } from "@takumi-rs/core";
import { container, text, image, percentage } from "@takumi-rs/helpers";
import { getSvgPath } from "figma-squircle";
import logger from "./logger.js";

/**
 * Renders a single-player profile card as a PNG, styled like the website's
 * player profile page (apps/web/src/components/PlayerStatsPage.tsx): an orange
 * gradient squircle header with an overlapping skin render, three gradient medal
 * squircles, and a grid of raw season-stat tiles.
 *
 * Uses Takumi (CSS/flexbox + figma-squircle masks) rather than the canvas
 * renderer that powers the Minecraft tab list — the web aesthetic (gradients,
 * superellipse corners, web fonts) is Takumi's strength.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSET_DIR = path.resolve(__dirname, "../../assets/player-card");
const MC_FONT_PATH = path.join(ASSET_DIR, "Minecraft.otf");
const SANS_FONT_PATH = path.join(ASSET_DIR, "Unbounded.ttf");

// Body text uses Unbounded (the website's --font-sans); numbers use the
// Minecraft face (the website's --font-mc).
const SANS = "Unbounded";

// ── theme tokens (mirrors apps/web dark theme) ───────────────────────────────
const PAPER = "#1a1412";
const PAPER2 = "#231c17";
const FG = "#f5f0eb";
const SUB = "rgb(245 240 235 / 0.5)";
const ORANGE_FROM = "#F97316";
const ORANGE_TO = "#FB923C";

const CARD_W = 860;
const MEDAL_W = 276, MEDAL_H = 96;
const TILE_W = 277, TILE_H = 92;
const SKIN_H = 300;

export interface PlayerCardStats {
  play_time_seconds: number;
  total_blocks_mined: number;
  total_blocks_placed: number;
  total_items_broken: number;
  mob_kills: number;
  player_kills: number;
  deaths: number;
  total_distance_m: number;
  jumps: number;
  animals_bred: number;
  fish_caught: number;
  times_slept: number;
}

export interface PlayerCardData {
  uuid: string;
  username: string;
  discordUsername?: string | null;
  /** Crown rank; 0 / undefined → "Unranked", no watermark. */
  rank: number;
  points: number;
  gold: number;
  silver: number;
  bronze: number;
  /** Current season name; omitted from the subtitle when absent. */
  season?: string | null;
  /** null → render the card with zeroed stat tiles (no stats this season yet). */
  stats: PlayerCardStats | null;
}

// ── value formatting (values arrive already in seconds / metres / counts — the
// Velocity endpoint stores them converted, so DO NOT re-divide by ticks/cm) ───
function formatPlaytime(seconds: number): string {
  const totalMin = Math.floor(seconds / 60);
  const days = Math.floor(totalMin / (60 * 24));
  const hours = Math.floor((totalMin % (60 * 24)) / 60);
  const mins = totalMin % 60;
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${mins}m`;
  return `${mins}m`;
}

function formatDistance(metres: number): string {
  if (metres >= 1000) {
    // Thousands-separated km, at most one decimal, no trailing ".0".
    return `${(metres / 1000).toLocaleString("en-US", { maximumFractionDigits: 1 })} km`;
  }
  return `${Math.round(metres)} m`;
}

const formatInt = (n: number) => Math.round(n).toLocaleString("en-US");

function statTiles(s: PlayerCardStats | null): Array<[string, string]> {
  const z: PlayerCardStats = s ?? {
    play_time_seconds: 0, total_blocks_mined: 0, total_blocks_placed: 0,
    total_items_broken: 0, mob_kills: 0, player_kills: 0, deaths: 0,
    total_distance_m: 0, jumps: 0, animals_bred: 0, fish_caught: 0, times_slept: 0,
  };
  return [
    ["Time Played", formatPlaytime(z.play_time_seconds)],
    ["Blocks Mined", formatInt(z.total_blocks_mined)],
    ["Blocks Placed", formatInt(z.total_blocks_placed)],
    ["Items Broken", formatInt(z.total_items_broken)],
    ["Mob Kills", formatInt(z.mob_kills)],
    ["Player Kills", formatInt(z.player_kills)],
    ["Deaths", formatInt(z.deaths)],
    ["Distance", formatDistance(z.total_distance_m)],
    ["Jumps", formatInt(z.jumps)],
    ["Animals Bred", formatInt(z.animals_bred)],
    ["Fish Caught", formatInt(z.fish_caught)],
    ["Times Slept", formatInt(z.times_slept)],
  ];
}

// True figma-squircle (superellipse) clip via an SVG mask — the same lib/curve
// the website uses. Sized in CSS px so it is devicePixelRatio-aware.
function squircle(w: number, h: number, r: number, smoothing = 1) {
  const d = getSvgPath({ width: w, height: h, cornerRadius: r, cornerSmoothing: smoothing });
  const svg = "data:image/svg+xml;base64," + Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><path d="${d}" fill="#fff"/></svg>`,
  ).toString("base64");
  return { maskImage: `url("${svg}")`, maskSize: `${w}px ${h}px`, maskRepeat: "no-repeat" } as any;
}

function dataUri(buf: Buffer | ArrayBuffer): string {
  return `data:image/png;base64,${Buffer.from(buf as any).toString("base64")}`;
}

type Img = { src: string; w: number; h: number };

async function fetchImg(url: string): Promise<Img | null> {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 5000);
    const r = await fetch(url, { signal: ctrl.signal }).finally(() => clearTimeout(t));
    if (!r.ok) return null;
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null; // not a PNG
    return { src: dataUri(buf), w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
  } catch {
    return null;
  }
}

// Preferred skin render is starlightskins (what the website uses); fall back to
// mc-heads.net body if it's unreachable. Natural dimensions come back so the box
// can preserve the render's aspect ratio.
async function fetchSkin(uuid: string): Promise<Img | null> {
  const starlight = await fetchImg(`https://starlightskins.lunareclipse.studio/render/default/${uuid}/full`);
  if (starlight) return starlight;
  logger.warn("playerCard: starlightskins unavailable, falling back to mc-heads");
  return fetchImg(`https://mc-heads.net/body/${uuid}/300`);
}

let renderer: Renderer | null = null;
function getRenderer(): Renderer {
  if (!renderer) {
    renderer = new Renderer({
      loadDefaultFonts: true, // glyph fallback for anything Unbounded lacks
      fonts: [
        { name: "Minecraft", data: fs.readFileSync(MC_FONT_PATH) },
        { name: SANS, data: fs.readFileSync(SANS_FONT_PATH) },
      ],
    });
  }
  return renderer;
}

function medal(label: string, value: number, from: string, to: string) {
  return container({
    style: {
      ...squircle(MEDAL_W, MEDAL_H, 28), width: MEDAL_W, height: MEDAL_H,
      display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 6,
      backgroundImage: `linear-gradient(135deg, ${from}, ${to})`,
    },
    children: [
      text(label.toUpperCase(), { fontFamily: SANS, fontSize: 13, color: "rgb(255 255 255 / 0.75)", letterSpacing: 1.5 }),
      text(String(value), { fontFamily: "Minecraft", fontSize: 34, color: "#ffffff" }),
    ],
  });
}

function statTile([label, value]: [string, string]) {
  return container({
    style: {
      ...squircle(TILE_W, TILE_H, 22), width: TILE_W, height: TILE_H, backgroundColor: PAPER2,
      paddingLeft: 18, display: "flex", flexDirection: "column", justifyContent: "center", gap: 6,
    },
    children: [
      text(value, { fontFamily: "Minecraft", fontSize: 26, color: FG }),
      text(label.toUpperCase(), { fontFamily: SANS, fontSize: 12, color: SUB, letterSpacing: 1 }),
    ],
  });
}

function chunk<T>(arr: T[], per: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += per) out.push(arr.slice(i, i + per));
  return out;
}

export async function generatePlayerCard(data: PlayerCardData): Promise<Buffer> {
  const skin = await fetchSkin(data.uuid);
  const skinW = skin ? Math.round(SKIN_H * (skin.w / skin.h)) : 0;

  const rankLabel = data.rank > 0 ? `Rank #${data.rank}` : "Unranked";
  const subtitle = data.season ? `${rankLabel}  ·  ${data.season}` : rankLabel;

  const headerLeft: any[] = [text(data.username, { fontFamily: SANS, fontSize: 38, fontWeight: 700, color: "#ffffff" })];
  if (data.discordUsername) {
    headerLeft.push(text(`@${data.discordUsername}`, { fontFamily: SANS, fontSize: 15, color: "rgb(255 255 255 / 0.55)" }));
  }
  headerLeft.push(text(subtitle, { fontFamily: SANS, fontSize: 15, color: "rgb(255 255 255 / 0.78)" }));

  const gradientChildren: any[] = [];
  if (data.rank > 0) {
    gradientChildren.push(text(`#${data.rank}`, {
      fontFamily: SANS, position: "absolute", top: -10, right: 28, fontSize: 150, fontWeight: 700,
      color: "rgb(255 255 255 / 0.10)",
    } as any));
  }
  gradientChildren.push(container({
    style: {
      position: "absolute", top: 0, left: 0, width: percentage(100), height: percentage(100),
      display: "flex", flexDirection: "row", alignItems: "center", justifyContent: "space-between",
      paddingLeft: 188, paddingRight: 36,
    } as any,
    children: [
      container({ style: { display: "flex", flexDirection: "column", gap: 4 }, children: headerLeft }),
      container({
        style: { display: "flex", flexDirection: "column", alignItems: "flex-end" },
        children: [
          text(String(data.points), { fontFamily: "Minecraft", fontSize: 48, color: "#ffffff" }),
          text("points", { fontFamily: SANS, fontSize: 15, color: "rgb(255 255 255 / 0.55)" }),
        ],
      }),
    ],
  }));

  const header = container({
    style: { position: "relative", width: CARD_W, height: 200 } as any,
    children: [
      container({
        style: {
          position: "absolute", top: 0, left: 0, width: CARD_W, height: 200,
          ...squircle(CARD_W, 200, 36), overflow: "hidden",
          backgroundImage: `linear-gradient(135deg, ${ORANGE_FROM}, ${ORANGE_TO})`,
        } as any,
        children: gradientChildren,
      }),
      // Skin: bottom-aligned to the card, taller so it pokes above (not clipped).
      // Placeholder block when the render is unavailable so the layout holds.
      skin
        ? image({ src: skin.src, width: skinW, height: SKIN_H, style: { position: "absolute", bottom: 0, left: 24 } as any })
        : container({ style: { position: "absolute", bottom: 0, left: 24, width: 125, height: SKIN_H } as any }),
    ],
  });

  const medals = container({
    style: { display: "flex", flexDirection: "row", gap: 16, width: CARD_W },
    children: [
      medal("Gold", data.gold, "#F59E0B", "#FBBF24"),
      medal("Silver", data.silver, "#9CA3AF", "#D1D5DB"),
      medal("Bronze", data.bronze, "#B45309", "#D97706"),
    ],
  });

  const tiles = statTiles(data.stats);
  const statGrid = container({
    style: { display: "flex", flexDirection: "column", gap: 14, width: CARD_W },
    children: chunk(tiles, 3).map((r) =>
      container({ style: { display: "flex", flexDirection: "row", gap: 14 }, children: r.map(statTile) }),
    ),
  });

  const root = container({
    style: {
      display: "flex", flexDirection: "column", alignItems: "center", gap: 18,
      paddingTop: 96, paddingLeft: 28, paddingRight: 28, paddingBottom: 28,
      backgroundColor: PAPER, width: CARD_W + 56,
    },
    children: [header, medals, statGrid],
  });

  return getRenderer().render(root, { format: "png", devicePixelRatio: 2 });
}
