import { ADDITIONAL_BLOCK_HUNT_PUZZLES } from "@/lib/blockHuntPrepared";

export type BlockHuntClueKind =
  | "data"
  | "destruction"
  | "redstone"
  | "shape"
  | "world"
  | "behaviour";

export type BlockHuntClue = {
  kind: BlockHuntClueKind;
  label: string;
  text: string;
};

export type BlockHuntPuzzle = {
  answer: string;
  texture: string;
  clues: readonly BlockHuntClue[];
};

export const BLOCK_HUNT_CLUES = 6;

const BLOCK_HUNT_EPOCH = Date.UTC(2026, 8, 2);

const ORIGINAL_BLOCK_HUNT_PUZZLES: readonly BlockHuntPuzzle[] = [
  {
    answer: "Respawn Anchor",
    texture: "respawn_anchor_side0",
    clues: [
      {
        kind: "redstone",
        label: "Signal profile",
        text: "Its comparator output and light level follow the same sequence: 0, 3, 7, 11, then 15.",
      },
      {
        kind: "destruction",
        label: "Resistance",
        text: "It has hardness 50 and blast resistance 1,200, and a piston cannot move it.",
      },
      {
        kind: "shape",
        label: "Spawn surface",
        text: "Mobs can spawn on it at charges 0 to 3, but not when it reaches its fourth charge.",
      },
      {
        kind: "data",
        label: "Engine quirks",
        text: "It is dragon-immune and makes a note block above it play a bass drum sound.",
      },
      {
        kind: "behaviour",
        label: "Charging",
        text: "Glowstone fills its four charges, changing both its texture and emitted light.",
      },
      {
        kind: "world",
        label: "Purpose",
        text: "It sets a respawn point in the Nether and explodes when used in the wrong dimension.",
      },
    ],
  },
  {
    answer: "Reinforced Deepslate",
    texture: "reinforced_deepslate_side",
    clues: [
      {
        kind: "destruction",
        label: "Resistance",
        text: "Its hardness is 55 and its blast resistance is 1,200. It is tougher to mine than obsidian.",
      },
      {
        kind: "data",
        label: "Boss immunity",
        text: "It is immune to destruction by both the Ender Dragon and the Wither.",
      },
      {
        kind: "redstone",
        label: "Unexpected surface",
        text: "Despite being unobtainable in survival, it is conductive and can support redstone dust.",
      },
      {
        kind: "destruction",
        label: "Tool rules",
        text: "No tool is considered correct for harvesting it, and pistons cannot move it.",
      },
      {
        kind: "world",
        label: "Availability",
        text: "It generates naturally, but cannot be crafted or collected in conventional survival.",
      },
      {
        kind: "world",
        label: "Location",
        text: "It forms the enormous, portal-like frame at the centre of an ancient city.",
      },
    ],
  },
  {
    answer: "Sculk Catalyst",
    texture: "sculk_catalyst_side",
    clues: [
      {
        kind: "data",
        label: "Block internals",
        text: "It is a ticking block entity, renders as a full cube, and has a luminance of 6.",
      },
      {
        kind: "destruction",
        label: "Mining profile",
        text: "Its hardness and blast resistance are both 3, and a hoe is the intended tool.",
      },
      {
        kind: "destruction",
        label: "Drops",
        text: "Silk Touch is needed to collect it; otherwise it can release exactly 5 XP.",
      },
      {
        kind: "redstone",
        label: "Physical rules",
        text: "It conducts redstone and supports dust, but a piston cannot move it.",
      },
      {
        kind: "behaviour",
        label: "Reaction",
        text: "When a nearby mob dies, it uses the dropped experience to spread a family of dark blocks.",
      },
      {
        kind: "world",
        label: "Habitat",
        text: "Look for this bone-ringed block in the deep dark, where it spreads sculk around itself.",
      },
    ],
  },
  {
    answer: "Creaking Heart",
    texture: "creaking_heart_side_inactive",
    clues: [
      {
        kind: "data",
        label: "Block internals",
        text: "It is a ticking block entity whose comparator output can range from 0 to 15.",
      },
      {
        kind: "destruction",
        label: "Mining profile",
        text: "It has hardness and blast resistance 10, is mined with an axe, and requires Silk Touch.",
      },
      {
        kind: "destruction",
        label: "Experience",
        text: "Breaking it without collecting the block can release 20 to 24 XP.",
      },
      {
        kind: "shape",
        label: "Activation rule",
        text: "It only becomes active when it sits between correctly aligned logs of one particular wood type.",
      },
      {
        kind: "behaviour",
        label: "Night-time link",
        text: "At night, it controls a hostile wooden puppet and transfers damage away from that mob.",
      },
      {
        kind: "world",
        label: "Biome",
        text: "This orange-cored block is the hidden heart of the Pale Garden.",
      },
    ],
  },
  {
    answer: "Copper Bulb",
    texture: "copper_bulb",
    clues: [
      {
        kind: "destruction",
        label: "Mining profile",
        text: "Its hardness is 3, its blast resistance is 6, and the intended tool is a pickaxe.",
      },
      {
        kind: "redstone",
        label: "Odd conductor",
        text: "Redstone dust can sit on top of it, but the block itself is not conductive.",
      },
      {
        kind: "redstone",
        label: "Stored state",
        text: "When lit it gives comparator output 15, and another pulse switches that state off again.",
      },
      {
        kind: "data",
        label: "Light curve",
        text: "Its four oxidation stages emit light levels 15, 12, 8, and 4 when active.",
      },
      {
        kind: "behaviour",
        label: "Ageing",
        text: "It slowly changes colour in the open air; honeycomb can freeze the current stage.",
      },
      {
        kind: "world",
        label: "Identity",
        text: "This toggleable copper light first appeared among the mechanisms of trial chambers.",
      },
    ],
  },
  {
    answer: "Bee Nest",
    texture: "bee_nest_front",
    clues: [
      {
        kind: "data",
        label: "Block internals",
        text: "It is a ticking block entity with hardness 0.3; a note block above it plays bass.",
      },
      {
        kind: "redstone",
        label: "Signal profile",
        text: "A comparator reads its internal level directly, producing each strength from 0 through 5.",
      },
      {
        kind: "destruction",
        label: "Harvesting",
        text: "An axe is the intended tool, but Silk Touch is required to collect the block itself.",
      },
      {
        kind: "data",
        label: "Material",
        text: "It is a flammable, opaque full cube and it blocks a beacon beam.",
      },
      {
        kind: "behaviour",
        label: "Occupants",
        text: "Its level rises as its flying occupants return carrying pollen from flowers.",
      },
      {
        kind: "world",
        label: "Origin",
        text: "This is the naturally generated home of bees, rather than the player-crafted version.",
      },
    ],
  },
  {
    answer: "Target",
    texture: "target_side",
    clues: [
      {
        kind: "destruction",
        label: "Mining profile",
        text: "Its hardness and blast resistance are both 0.5; surprisingly, a hoe is the intended tool.",
      },
      {
        kind: "redstone",
        label: "Block internals",
        text: "It emits power without using a block entity, while still behaving as a conductive full cube.",
      },
      {
        kind: "data",
        label: "Material",
        text: "It is flammable, blocks skylight, supports redstone dust, and uses quartz as its map colour.",
      },
      {
        kind: "world",
        label: "Recipe",
        text: "Four pieces of redstone dust surround a hay bale in its crafting recipe.",
      },
      {
        kind: "behaviour",
        label: "Precision",
        text: "A projectile produces signal strength 1 to 15 depending on how close it lands to the centre.",
      },
      {
        kind: "shape",
        label: "Appearance",
        text: "Every face displays the red-and-white rings of a bullseye.",
      },
    ],
  },
  {
    answer: "Sponge",
    texture: "sponge",
    clues: [
      {
        kind: "destruction",
        label: "Mining profile",
        text: "Its hardness and blast resistance are both 0.6, and a hoe is the fastest intended tool.",
      },
      {
        kind: "data",
        label: "Physical rules",
        text: "It is a piston-movable full cube that conducts redstone and blocks beacon beams.",
      },
      {
        kind: "destruction",
        label: "Harvesting",
        text: "It is non-flammable, drops itself without Silk Touch, and releases no experience.",
      },
      {
        kind: "behaviour",
        label: "Capacity",
        text: "One placement can remove as many as 65 connected water blocks within its reach.",
      },
      {
        kind: "world",
        label: "Dimension trick",
        text: "Its saturated form dries instantly when placed in the Nether.",
      },
      {
        kind: "world",
        label: "Location",
        text: "Ocean monuments contain rooms of this yellow block, which turns dark when it absorbs water.",
      },
    ],
  },
  {
    answer: "Honey Block",
    texture: "honey_side",
    clues: [
      {
        kind: "shape",
        label: "Collision shape",
        text: "Its external collision height is 15⁄16 of a block, and it is not an opaque full cube.",
      },
      {
        kind: "destruction",
        label: "Mining profile",
        text: "Both hardness and blast resistance are 0, so the intended tool is simply your hand.",
      },
      {
        kind: "redstone",
        label: "Signal rules",
        text: "It neither conducts redstone nor supports dust, and it does not block a beacon beam.",
      },
      {
        kind: "behaviour",
        label: "Piston rule",
        text: "It is sticky to most blocks, but deliberately refuses to stick to slime blocks.",
      },
      {
        kind: "behaviour",
        label: "Movement",
        text: "It shortens jumps, slows anything walking through it, and lets players slide down its sides.",
      },
      {
        kind: "world",
        label: "Recipe",
        text: "Four filled bottles craft this translucent amber block.",
      },
    ],
  },
  {
    answer: "Ancient Debris",
    texture: "ancient_debris_side",
    clues: [
      {
        kind: "destruction",
        label: "Resistance",
        text: "It combines hardness 30 with blast resistance 1,200, yet pistons can still move it.",
      },
      {
        kind: "data",
        label: "Block internals",
        text: "It is a conductive full cube, supports redstone dust, and makes note blocks play harp.",
      },
      {
        kind: "destruction",
        label: "Drops",
        text: "It needs a high-tier pickaxe, but neither Silk Touch nor Fortune changes its block drop.",
      },
      {
        kind: "world",
        label: "Generation",
        text: "It forms small, hidden veins and never generates naturally exposed to air.",
      },
      {
        kind: "behaviour",
        label: "Heat proof",
        text: "The dropped item cannot be destroyed by fire or lava, and the block survives explosions.",
      },
      {
        kind: "world",
        label: "Use",
        text: "Smelting this rare Nether ore is the first step towards making netherite equipment.",
      },
    ],
  },
];

export const BLOCK_HUNT_PUZZLES: readonly BlockHuntPuzzle[] = [
  ...ORIGINAL_BLOCK_HUNT_PUZZLES,
  ...ADDITIONAL_BLOCK_HUNT_PUZZLES,
];

export function normaliseBlockGuess(value: string): string {
  return value.trim().replace(/\s+/g, " ").toLocaleLowerCase("en-GB");
}

export function getBlockHuntDailyNumber(date = new Date()): number {
  const utcDay = Date.UTC(
    date.getUTCFullYear(),
    date.getUTCMonth(),
    date.getUTCDate(),
  );
  return Math.max(1, Math.floor((utcDay - BLOCK_HUNT_EPOCH) / 86_400_000) + 1);
}

export function getBlockHuntDailyPuzzle(date = new Date()): BlockHuntPuzzle {
  const dailyNumber = getBlockHuntDailyNumber(date);
  return BLOCK_HUNT_PUZZLES[(dailyNumber - 1) % BLOCK_HUNT_PUZZLES.length];
}
