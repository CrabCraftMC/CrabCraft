export const CATEGORY_ORDER = [
  "story",
  "nether",
  "end",
  "adventure",
  "husbandry",
] as const;

export type AdvancementCategory = (typeof CATEGORY_ORDER)[number];

export const CATEGORY_LABELS: Record<AdvancementCategory, string> = {
  story: "Story",
  nether: "Nether",
  end: "End",
  adventure: "Adventure",
  husbandry: "Husbandry",
};

export function isValidCategory(value: string): value is AdvancementCategory {
  return (CATEGORY_ORDER as readonly string[]).includes(value);
}
