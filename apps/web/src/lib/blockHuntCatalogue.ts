import {
  getHuntCatalogue,
  getHuntEntry,
  HUNT_ATLAS_CELL_SIZE,
  HUNT_ATLAS_COLUMNS,
  searchHuntEntries,
  type HuntEntry,
} from "@/lib/huntCatalogue";

export const BLOCK_HUNT_ATLAS_COLUMNS = HUNT_ATLAS_COLUMNS;
export const BLOCK_HUNT_ATLAS_CELL_SIZE = HUNT_ATLAS_CELL_SIZE;
export type BlockHuntBlock = HuntEntry;
export const BLOCK_HUNT_BLOCKS = getHuntCatalogue("block").entries;
export const getBlockHuntBlock = (name: string) => getHuntEntry("block", name);
export const searchBlockHuntBlocks = (value: string, limit = 8) =>
  searchHuntEntries("block", value, limit);
