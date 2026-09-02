import {
  BLOCK_HUNT_CLUES,
  BLOCK_HUNT_PUZZLES,
  getBlockHuntDailyNumber,
  type BlockHuntClue,
} from "@/lib/blockHunt";
import { ITEM_HUNT_PUZZLES } from "@/lib/itemHunt";
import { MOB_HUNT_PUZZLES } from "@/lib/mobHunt";
import type { HuntKind } from "@/lib/huntCatalogue";

export type HuntPuzzle = {
  answer: string;
  clues: readonly BlockHuntClue[];
};

export type HuntConfig = {
  kind: HuntKind;
  name: string;
  singular: string;
  plural: string;
  route: string;
  icon: string;
};

export const HUNT_CLUES = BLOCK_HUNT_CLUES;

export const HUNT_CONFIG: Record<HuntKind, HuntConfig> = {
  block: {
    kind: "block",
    name: "Block Hunt",
    singular: "block",
    plural: "blocks",
    route: "/games/block-hunt",
    icon: "/textures/hunt/icons/block.webp",
  },
  item: {
    kind: "item",
    name: "Item Hunt",
    singular: "item",
    plural: "items",
    route: "/games/item-hunt",
    icon: "/textures/hunt/icons/item.webp",
  },
  mob: {
    kind: "mob",
    name: "Mob Hunt",
    singular: "mob",
    plural: "mobs",
    route: "/games/mob-hunt",
    icon: "/textures/hunt/icons/mob.webp",
  },
};

export const HUNT_KINDS = Object.keys(HUNT_CONFIG) as HuntKind[];

const puzzles: Record<HuntKind, readonly HuntPuzzle[]> = {
  block: BLOCK_HUNT_PUZZLES,
  item: ITEM_HUNT_PUZZLES,
  mob: MOB_HUNT_PUZZLES,
};

export function getHuntDailyNumber(date = new Date()): number {
  return getBlockHuntDailyNumber(date);
}

export function getHuntDailyPuzzle(
  kind: HuntKind,
  date = new Date(),
): HuntPuzzle {
  const dailyNumber = getHuntDailyNumber(date);
  const kindPuzzles = puzzles[kind];
  return kindPuzzles[(dailyNumber - 1) % kindPuzzles.length];
}

export function getHuntPuzzleCount(kind: HuntKind): number {
  return puzzles[kind].length;
}
