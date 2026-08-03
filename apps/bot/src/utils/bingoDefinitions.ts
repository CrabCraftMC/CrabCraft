import type { BingoTaskDefinition } from "@crabcraft/db/queries/bingo";

export const FIRST_BINGO_CARD = {
  number: 1,
  startsAt: 1_785_711_600,
  endsAt: 1_786_316_400,
  tasks: [
    { id: "leash_rabbit", label: "Leash a rabbit" },
    { id: "kill_full_iron_mob", label: "Kill a mob wearing full iron armour" },
    { id: "light_copper_bulb", label: "Light an unlit Copper Bulb by placing a Redstone Torch beside it" },
    { id: "bucket_axolotl", label: "Catch an axolotl in a bucket" },
    { id: "grow_huge_mushroom", label: "Grow a huge mushroom using bone meal" },
    { id: "harvest_cactus_flower", label: "Harvest a cactus flower" },
    { id: "milk_goat", label: "Milk a Goat" },
    { id: "suffocate_hostile_with_sand", label: "Kill a hostile mob by suffocating it with falling Sand" },
    { id: "eat_suspicious_stew", label: "Eat a Suspicious Stew" },
    { id: "build_dripleaf_column", label: "Build a 10-block-tall big dripleaf column" },
    { id: "stun_ravager", label: "Stun a ravager by blocking its attack with a shield" },
    { id: "armour_wolf", label: "Equip a wolf with wolf armour" },
    { id: "give_mob_hat", label: "Drop a helmet or carved pumpkin for a mob to wear" },
    { id: "tame_parrot", label: "Tame a parrot" },
    { id: "carve_pumpkin", label: "Carve a pumpkin with shears" },
    { id: "lectern_ignite_tnt", label: "Turn a Lectern page to ignite directly adjacent TNT" },
  ] satisfies BingoTaskDefinition[],
} as const;

export const PREPARED_BINGO_CARDS = [FIRST_BINGO_CARD];

/** Task IDs that have a deployed Paper detector, not merely a planned card entry. */
export const SUPPORTED_BINGO_TASK_IDS = new Set(
  FIRST_BINGO_CARD.tasks.map((task) => task.id),
);
