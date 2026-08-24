import type { BingoTaskDefinition } from "@crabcraft/db/queries/bingo";

export const FIRST_BINGO_CARD = {
  number: 1,
  startsAt: 1_785_711_600,
  endsAt: 1_786_316_400,
  tasks: [
    { id: "grow_tree_in_nether", label: "Grow a tree in the Nether" },
    { id: "play_five_goat_horns", label: "Play five different goat horn types" },
    {
      id: "connect_all_ore_types",
      label: "Place all 10 ore families + Ancient Debris together (normal/deepslate count as one)",
    },
    { id: "activate_totem", label: "Activate a Totem of Undying" },
    { id: "breed_mule", label: "Breed a horse and donkey to produce a mule" },
    { id: "kill_hostile_with_anvil", label: "Kill a hostile mob with a falling anvil" },
    { id: "two_creepers_one_boat", label: "Place a boat and get two creepers into it" },
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

export const SECOND_BINGO_CARD = {
  number: 2,
  startsAt: 1_786_348_800,
  endsAt: 1_786_921_200,
  tasks: [
    { id: "shear_bogged", label: "Shear a Bogged" },
    { id: "ring_bell_projectile", label: "Ring a bell with a projectile fired from 10+ blocks away" },
    { id: "berry_bush_kill", label: "Plant a sweet berry bush that kills a hostile mob" },
    { id: "dry_sponge_nether", label: "Dry a wet sponge by placing it in the Nether" },
    { id: "five_parrots_dance", label: "Play a jukebox to make all five parrot colours dance together" },
    {
      id: "detonate_tnt_minecart",
      label: "Detonate a TNT minecart by lighting fire beneath it with flint and steel or a fire charge",
    },
    { id: "target_opens_door", label: "Shoot a target block from 10+ blocks away to open an adjacent door" },
    { id: "collect_turtle_scute", label: "Pick up a scute shed by a maturing turtle" },
    { id: "shelf_hotbar_swap", label: "Use three connected powered shelves to swap your entire hotbar" },
    { id: "remove_pig_saddle", label: "Remove a pig’s saddle with shears without killing it" },
    { id: "explorer_map_trade", label: "Buy an explorer map from a cartographer villager" },
    { id: "self_arrow_totem", label: "Fire an arrow, then survive that same arrow using a Totem of Undying" },
    { id: "equip_piglin_gold_armour", label: "Give one Piglin a full set of golden armour to equip" },
    { id: "leashed_bee_sting", label: "Leash a bee and have it sting another mob" },
    { id: "creeper_rings_bell", label: "Place a pressure plate beside a bell and let a creeper ring it" },
    { id: "mine_copper_golem_statue", label: "Mine a copper golem statue so it drops" },
  ] satisfies BingoTaskDefinition[],
} as const;

export const THIRD_BINGO_CARD = {
  number: 3,
  startsAt: 1_786_953_600,
  endsAt: 1_787_526_000,
  tasks: [
    { id: "sulfur_cube_tnt_ignite", label: "Feed TNT to a Sulfur Cube, then ignite it" },
    { id: "breed_third_colour_sheep", label: "Breed two sheep to produce a third colour" },
    { id: "unlock_ominous_vault", label: "Unlock an Ominous Vault" },
    { id: "fox_uses_totem", label: "Give a fox a Totem of Undying and make it activate it" },
    { id: "tame_nautilus", label: "Tame a Nautilus" },
    { id: "johnny_vindicator_kill", label: "Name a Vindicator Johnny and have it kill a hostile mob" },
    { id: "hook_ghast", label: "Hook a Ghast with a fishing rod" },
    { id: "water_bottle_extinguish_three", label: "Extinguish three burning mobs with one splash water bottle" },
    { id: "shulker_bullet_duplicate", label: "Use a Shulker bullet aimed at you to duplicate another Shulker" },
    { id: "warm_ridden_strider", label: "Ride a shivering Strider into lava to warm it up" },
    { id: "raid_bell_reveal_three", label: "Ring a bell during a raid to reveal three raiders" },
    { id: "piercing_arrow_hit_three", label: "Hit three mobs with one Piercing crossbow arrow" },
    { id: "leashed_frog_froglight", label: "Keep a Frog leashed while it creates a Froglight" },
    { id: "charged_creeper_mob_head", label: "Charge a Creeper, then use it to obtain a mob head" },
    { id: "golden_dandelion_hoglin", label: "Stop a baby Hoglin ageing with a Golden Dandelion" },
    { id: "four_copper_trumpet_sounds", label: "Play all four copper-trumpet sounds with Note Blocks" },
  ] satisfies BingoTaskDefinition[],
} as const;

export const FOURTH_BINGO_CARD = {
  number: 4,
  startsAt: 1_787_558_400,
  endsAt: 1_788_130_800,
  tasks: [
    {
      id: "sulfur_cube_diamond_bucket",
      label: "Catch a Sulfur Cube carrying a Diamond Block in a bucket",
    },
    { id: "snow_golem_kills_blaze", label: "Build a Snow Golem and have it kill a Blaze" },
    { id: "four_by_four_nether_portal", label: "Build and light a Nether portal with a 4×4 interior" },
    { id: "crossbow_firework_kill_two", label: "Kill two hostile mobs with one crossbow Firework" },
    {
      id: "happy_ghast_hostile_boat",
      label: "Leash a Boat carrying a hostile mob to a harnessed Happy Ghast",
    },
    { id: "spear_hit_three", label: "Hit three mobs with one Spear charge" },
    {
      id: "silverfish_hide_in_stone",
      label: "Name a Silverfish, then let it hide inside stone",
    },
    { id: "tree_with_bee_nest", label: "Use bone meal to grow a tree containing a Bee Nest" },
    {
      id: "brown_mooshroom_wither_stew",
      label: "Feed a Brown Mooshroom a Wither Rose, then milk its Suspicious Stew",
    },
    { id: "piston_push_twelve", label: "Use a Lever attached to a Piston to push 12 blocks" },
    {
      id: "ender_pearl_teleport_hundred",
      label: "Teleport 100+ horizontal blocks with one Ender Pearl",
    },
    { id: "reflected_breeze_wind_charge", label: "Kill a Breeze with its own deflected Wind Charge" },
    {
      id: "projectile_smash_filled_pot",
      label: "Smash a filled Decorated Pot with a projectile fired from 10+ blocks",
    },
    {
      id: "cure_poison_honey_bottle",
      label: "Cure Poison by drinking a Honey Bottle",
    },
    {
      id: "freeze_skeleton_stray",
      label: "Freeze a Skeleton into a Stray using Powder Snow you placed",
    },
    {
      id: "sculk_catalyst_player_kill",
      label: "Kill a hostile mob beside a Sculk Catalyst to spread Sculk",
    },
  ] satisfies BingoTaskDefinition[],
} as const;

export const PREPARED_BINGO_CARDS = [
  FIRST_BINGO_CARD,
  SECOND_BINGO_CARD,
  THIRD_BINGO_CARD,
  FOURTH_BINGO_CARD,
];

/** Task IDs that have a deployed Paper detector, not merely a planned card entry. */
export const SUPPORTED_BINGO_TASK_IDS = new Set(
  PREPARED_BINGO_CARDS.flatMap((card) => card.tasks.map((task) => task.id)),
);
