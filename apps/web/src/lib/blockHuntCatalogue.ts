import catalogue from "@/data/block-hunt-blocks.json";
import { normaliseBlockGuess } from "@/lib/blockHunt";

export const BLOCK_HUNT_ATLAS_COLUMNS = 32;
export const BLOCK_HUNT_ATLAS_CELL_SIZE = 32;

export type BlockHuntBlock = {
  id: string;
  name: string;
  aliases: string[];
  sprite: number;
};

export const BLOCK_HUNT_BLOCKS = catalogue as BlockHuntBlock[];

const searchTerms = new Map(
  BLOCK_HUNT_BLOCKS.map((block) => [
    block.id,
    [block.name, ...block.aliases].map(normaliseBlockGuess),
  ]),
);

const blockLookup = new Map<string, BlockHuntBlock>();
for (const block of BLOCK_HUNT_BLOCKS) {
  for (const name of [block.name, ...block.aliases]) {
    blockLookup.set(normaliseBlockGuess(name), block);
  }
}

export function getBlockHuntBlock(name: string): BlockHuntBlock | undefined {
  return blockLookup.get(normaliseBlockGuess(name));
}

export function searchBlockHuntBlocks(
  value: string,
  limit = 8,
): BlockHuntBlock[] {
  const query = normaliseBlockGuess(value);
  if (!query) return [];

  const startsWith: BlockHuntBlock[] = [];
  const includes: BlockHuntBlock[] = [];
  for (const block of BLOCK_HUNT_BLOCKS) {
    const terms = searchTerms.get(block.id) ?? [];
    if (terms.some((term) => term.startsWith(query))) startsWith.push(block);
    else if (terms.some((term) => term.includes(query))) includes.push(block);
  }
  return [...startsWith, ...includes].slice(0, limit);
}
