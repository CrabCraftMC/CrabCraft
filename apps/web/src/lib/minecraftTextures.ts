/**
 * Manifest of Minecraft texture files required by the Wrapped story flow.
 * Paths are relative to `apps/web/public/minecraft/`. The build-time
 * downloader (scripts/download-textures.ts) prefers to copy from
 * `_external/crabcraft-wrapped-main/public/minecraft/` if present, otherwise
 * fetches from Mojang's bedrock-samples repository.
 */
export const REQUIRED_MC_TEXTURES = [
  // Cursors / scene icons (one per scene)
  "item/nether_star.png",
  "item/clock_16.png",
  "item/compass_16.png",
  "item/diamond_pickaxe.png",
  "item/diamond_sword.png",
  "item/stick.png",
  "item/rabbit_foot.png",
  "item/spyglass.png",
  // Combat extras
  "item/bone.png",
  // Distance transport
  "item/iron_boots.png",
  "item/oak_boat.png",
  "item/elytra.png",
  "item/saddle.png",
  "item/heart_of_the_sea.png",
  "mob_effect/speed.png",
  "mob_effect/dolphins_grace.png",
  // Mining rain pool
  "block/stone.png",
  "block/dirt.png",
  "block/cobblestone.png",
  "block/diamond_ore.png",
  "block/iron_ore.png",
  "block/coal_ore.png",
  "block/oak_planks.png",
  "block/grass_block_top.png",
  // Building tower
  "block/bricks.png",
  // Fun facts
  "item/wheat.png",
  "item/cod.png",
  "item/emerald.png",
  "item/enchanted_book.png",
  "block/red_wool.png",
  // Crafting
  "block/crafting_table_front.png",
  // Combat HUD
  "gui/sprites/hud/heart/full.png",
  // Particles
  "particle/glow.png",
] as const;

export const POPULAR_TOP_BLOCKS = [
  "stone",
  "cobblestone",
  "deepslate",
  "andesite",
  "diorite",
  "granite",
  "iron_ore",
  "deepslate_iron_ore",
  "coal_ore",
  "deepslate_coal_ore",
  "diamond_ore",
  "deepslate_diamond_ore",
  "ancient_debris",
  "netherrack",
  "oak_log",
  "spruce_log",
  "birch_log",
  "dirt",
  "grass_block_top",
];
