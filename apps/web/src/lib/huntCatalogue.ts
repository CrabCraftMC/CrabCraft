import blockCatalogue from "@/data/block-hunt-blocks.json";
import itemCatalogue from "@/data/item-hunt-items.json";
import mobCatalogue from "@/data/mob-hunt-mobs.json";

export type HuntKind = "block" | "item" | "mob";

export const HUNT_ATLAS_COLUMNS = 32;
export const HUNT_ATLAS_CELL_SIZE = 32;

export type HuntEntry = {
  id: string;
  name: string;
  aliases: string[];
  article: string;
  sprite: number;
};

type HuntCatalogue = {
  version: string;
  source: string;
  sourceRevision: number;
  entries: HuntEntry[];
};

const catalogues: Record<HuntKind, HuntCatalogue> = {
  block: blockCatalogue as HuntCatalogue,
  item: itemCatalogue as HuntCatalogue,
  mob: mobCatalogue as HuntCatalogue,
};

const searchTerms = new Map<HuntKind, Map<string, string[]>>();
const lookups = new Map<HuntKind, Map<string, HuntEntry>>();

export function normaliseHuntGuess(value: string): string {
  return value.trim().replace(/\s+/g, " ").toLocaleLowerCase("en-GB");
}

for (const kind of Object.keys(catalogues) as HuntKind[]) {
  const terms = new Map<string, string[]>();
  const lookup = new Map<string, HuntEntry>();
  for (const entry of catalogues[kind].entries) {
    const names = [entry.name, ...entry.aliases];
    terms.set(entry.id, names.map(normaliseHuntGuess));
    for (const name of names) lookup.set(normaliseHuntGuess(name), entry);
  }
  searchTerms.set(kind, terms);
  lookups.set(kind, lookup);
}

export function getHuntCatalogue(kind: HuntKind): HuntCatalogue {
  return catalogues[kind];
}

export function getHuntEntry(
  kind: HuntKind,
  name: string,
): HuntEntry | undefined {
  return lookups.get(kind)?.get(normaliseHuntGuess(name));
}

export function searchHuntEntries(
  kind: HuntKind,
  value: string,
  limit = 8,
): HuntEntry[] {
  const query = normaliseHuntGuess(value);
  if (!query) return [];

  const startsWith: HuntEntry[] = [];
  const includes: HuntEntry[] = [];
  const terms = searchTerms.get(kind);
  for (const entry of catalogues[kind].entries) {
    const entryTerms = terms?.get(entry.id) ?? [];
    if (entryTerms.some((term) => term.startsWith(query))) startsWith.push(entry);
    else if (entryTerms.some((term) => term.includes(query))) includes.push(entry);
  }
  return [...startsWith, ...includes].slice(0, limit);
}
