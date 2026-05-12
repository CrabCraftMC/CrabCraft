export const SCENE_IDS = [
  "intro",
  "playtime",
  "distance",
  "mining",
  "combat",
  "building",
  "fun-facts",
  "rankings",
  "summary",
] as const;

export type SceneId = (typeof SCENE_IDS)[number];

export const SCENE_TITLES: Record<SceneId, string> = {
  intro: "Welcome",
  playtime: "Play Time",
  distance: "Distance",
  mining: "Mining",
  combat: "Combat",
  building: "Building & Crafting",
  "fun-facts": "Fun Facts",
  rankings: "Rankings",
  summary: "That's a wrap",
};

// Per-slide color triplet [primary, dark, secondary] — used by background tint
// and accent borders. OKLCH values mirror the reference repo's slide-config.
export const SLIDE_COLORS: Array<[string, string, string]> = [
  ["oklch(0.75 0.13 22)", "oklch(0.30 0.08 295)", "oklch(0.62 0.16 305)"],
  ["oklch(0.78 0.15 70)", "oklch(0.32 0.09 60)", "oklch(0.65 0.14 50)"],
  ["oklch(0.74 0.14 50)", "oklch(0.30 0.10 40)", "oklch(0.68 0.13 70)"],
  ["oklch(0.70 0.15 145)", "oklch(0.28 0.10 130)", "oklch(0.62 0.13 100)"],
  ["oklch(0.62 0.20 25)", "oklch(0.25 0.12 20)", "oklch(0.55 0.18 10)"],
  ["oklch(0.70 0.12 230)", "oklch(0.28 0.10 250)", "oklch(0.65 0.13 210)"],
  ["oklch(0.68 0.16 300)", "oklch(0.28 0.12 290)", "oklch(0.62 0.14 270)"],
  ["oklch(0.82 0.16 90)", "oklch(0.32 0.10 70)", "oklch(0.72 0.15 100)"],
  ["oklch(0.75 0.14 35)", "oklch(0.30 0.10 50)", "oklch(0.72 0.15 85)"],
];

// RGB triplets (0..1) consumed by the dither shader's `waveColor` uniform.
// Roughly correspond to each slide's primary OKLCH tint.
export const SLIDE_WAVE_COLORS: Array<[number, number, number]> = [
  [1.0, 0.55, 0.45], // intro - warm coral
  [1.0, 0.78, 0.3], // playtime - amber
  [1.0, 0.6, 0.3], // distance - orange
  [0.3, 0.85, 0.5], // mining - emerald
  [1.0, 0.3, 0.4], // combat - crimson
  [0.4, 0.65, 1.0], // building - sky
  [0.75, 0.55, 1.0], // fun facts - violet
  [1.0, 0.85, 0.3], // rankings - gold
  [1.0, 0.6, 0.4], // summary - coral-gold
];

// Per-slide cursor texture (Minecraft item PNG). Falls back to default on
// the page when null.
export const SLIDE_CURSORS: Array<string | null> = [
  "/minecraft/item/nether_star.png",
  "/minecraft/item/clock_16.png",
  "/minecraft/item/compass_16.png",
  "/minecraft/item/diamond_pickaxe.png",
  "/minecraft/item/diamond_sword.png",
  "/minecraft/item/stick.png",
  "/minecraft/item/rabbit_foot.png",
  "/minecraft/item/spyglass.png",
  "/minecraft/item/nether_star.png",
];

export const TOTAL_SCENES = SCENE_IDS.length;
