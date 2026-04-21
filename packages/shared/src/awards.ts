// Shared award types. Award definitions themselves live in Postgres
// (the `awards` table, seeded from packages/db/seeds/awards.json);
// this file only carries the type shapes so callers can type against
// them without importing from @crabcraft/db.

export type AwardUnit = "int" | "ticks" | "cm" | "tenths_of_heart";
export type AwardBucket =
  | "combat"
  | "mining"
  | "crafting"
  | "building"
  | "items"
  | "food"
  | "movement"
  | "misc";

export interface AwardMeta {
  id: string;
  title: string;
  desc: string;
  unit: AwardUnit;
  bucket: AwardBucket;
  icon: string;
}

export const AWARD_BUCKET_LABELS: Record<AwardBucket, string> = {
  combat: "Combat",
  mining: "Mining",
  crafting: "Crafting",
  building: "Building",
  items: "Items",
  food: "Food",
  movement: "Movement",
  misc: "Misc",
};
