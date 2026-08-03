import type { BingoTaskDefinition } from "@crabcraft/db/queries/bingo";

export const FIRST_BINGO_CARD = {
  number: 1,
  startsAt: 1_785_711_600,
  endsAt: 1_786_316_400,
  tasks: [
    { id: "grow_tree_in_nether", label: "Grow a tree in the Nether" },
    { id: "play_five_goat_horns", label: "Play five different goat horn types" },
    { id: "connect_all_ore_types", label: "Place all 11 ore types in one connected cluster" },
    { id: "activate_totem", label: "Activate a Totem of Undying" },
    { id: "breed_mule", label: "Breed a horse and donkey to produce a mule" },
    { id: "kill_hostile_with_anvil", label: "Kill a hostile mob with a falling anvil" },
    { id: "two_creepers_one_boat", label: "Get two creepers into the same boat" },
    { id: "ignite_campfire_from_distance", label: "Light a campfire with a flaming projectile from 10+ blocks" },
    { id: "breed_sniffers_collect_egg", label: "Breed two Sniffers and collect their Sniffer Egg" },
    { id: "cure_zombie_villager", label: "Cure a zombie villager" },
    { id: "gain_axolotl_regeneration", label: "Gain Regeneration from an axolotl" },
    { id: "equip_piglin_brute_axe", label: "Give a Piglin Brute an enchanted golden axe to equip" },
    { id: "kill_hostile_from_camel", label: "Kill a hostile mob while riding a camel" },
    { id: "duplicate_allay", label: "Give a dancing Allay an Amethyst Shard to duplicate it" },
    { id: "collapse_scaffolding_tower", label: "Build a 64-block Scaffolding tower and break its base" },
    { id: "breed_trusting_fox", label: "Breed foxes to gain a cub's trust" },
  ] satisfies BingoTaskDefinition[],
} as const;

export const PREPARED_BINGO_CARDS = [FIRST_BINGO_CARD];

/** Task IDs that have a deployed Paper detector, not merely a planned card entry. */
export const SUPPORTED_BINGO_TASK_IDS = new Set(
  FIRST_BINGO_CARD.tasks.map((task) => task.id),
);
