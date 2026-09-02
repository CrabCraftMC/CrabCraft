export const BLOCK_HUNT_GLOSSARY = [
  {
    term: "ticking block entity",
    definition:
      "A block that stores extra data and updates its behaviour about 20 times a second.",
  },
  {
    term: "opaque full cube",
    definition: "A complete cube that blocks light passing through it.",
  },
  {
    term: "blast resistance",
    definition:
      "How strongly a block resists explosions. Higher values are tougher.",
  },
  {
    term: "comparator output",
    definition: "The redstone signal strength a comparator reads from this block.",
  },
  {
    term: "oxidation stages",
    definition: "The four ageing states copper passes through over time.",
  },
  {
    term: "collision height",
    definition: "The height of the solid shape that players and mobs bump into.",
  },
  {
    term: "signal strength",
    definition: "A redstone power level from 0 to 15.",
  },
  {
    term: "block entity",
    definition: "A block that stores extra data, such as contents or a changing state.",
  },
  {
    term: "luminance",
    definition: "The light level emitted by a block, from 0 to 15.",
  },
  {
    term: "hardness",
    definition: "How long a block takes to break. Higher values take longer.",
  },
  {
    term: "conductive",
    definition: "Redstone power can pass through this solid block.",
  },
  {
    term: "light level",
    definition: "A brightness value from 0 to 15.",
  },
  {
    term: "full cube",
    definition: "Its shape fills the entire one-block space.",
  },
  {
    term: "Silk Touch",
    definition: "An enchantment that makes many blocks drop themselves unchanged.",
  },
  {
    term: "map colour",
    definition: "The colour used when this block appears on a filled map.",
  },
  {
    term: "XP",
    definition: "Experience points used for enchanting and equipment repairs.",
  },
] as const;

const glossaryLookup = new Map(
  BLOCK_HUNT_GLOSSARY.map(({ term, definition }) => [
    term.toLocaleLowerCase("en-GB"),
    definition,
  ]),
);

const glossaryPattern = new RegExp(
  `\\b(${BLOCK_HUNT_GLOSSARY.map(({ term }) =>
    term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
  ).join("|")})\\b`,
  "gi",
);

export type BlockHuntGlossaryPart = {
  text: string;
  definition?: string;
};

export function parseBlockHuntGlossary(text: string): BlockHuntGlossaryPart[] {
  return text
    .split(glossaryPattern)
    .filter(Boolean)
    .map((part) => ({
      text: part,
      definition: glossaryLookup.get(part.toLocaleLowerCase("en-GB")),
    }));
}
