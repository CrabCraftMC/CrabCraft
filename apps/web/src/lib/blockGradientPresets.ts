export const BLOCK_GRADIENT_PRESETS = [
  {
    id: "all",
    name: "Full palette",
    description: "Every block in the gradient palette.",
  },
  {
    id: "survival",
    name: "Survival obtainable",
    description: "Blocks with obtainable item forms in vanilla Survival.",
  },
  {
    id: "renewable",
    name: "Renewable",
    description: "Survival blocks with unlimited vanilla sources.",
  },
  {
    id: "no_silk_touch",
    name: "No Silk Touch",
    description: "Blocks practical to collect without a Silk Touch tool.",
  },
  {
    id: "bulk_building",
    name: "Bulk building",
    description: "Common building families that are practical in large quantities.",
  },
] as const

export type BlockGradientPresetId = (typeof BLOCK_GRADIENT_PRESETS)[number]["id"]

type BlockPresetBlock = {
  id: string
}

const presetIds = new Set<string>(BLOCK_GRADIENT_PRESETS.map((preset) => preset.id))

const makeSet = (ids: string[]) => new Set(ids)

const ORE_BLOCKS = makeSet([
  "coal_ore",
  "deepslate_coal_ore",
  "iron_ore",
  "deepslate_iron_ore",
  "copper_ore",
  "deepslate_copper_ore",
  "gold_ore",
  "deepslate_gold_ore",
  "redstone_ore",
  "deepslate_redstone_ore",
  "emerald_ore",
  "deepslate_emerald_ore",
  "lapis_ore",
  "deepslate_lapis_ore",
  "diamond_ore",
  "deepslate_diamond_ore",
  "nether_gold_ore",
  "nether_quartz_ore",
])

const CREATIVE_ONLY_OR_INACCESSIBLE_BLOCKS = makeSet([
  "barrier",
  "bedrock",
  "budding_amethyst",
  "chain_command_block",
  "chorus_plant",
  "command_block",
  "dirt_path",
  "end_portal_frame",
  "farmland",
  "frogspawn",
  "infested_chiseled_stone_bricks",
  "infested_cobblestone",
  "infested_cracked_stone_bricks",
  "infested_deepslate",
  "infested_mossy_stone_bricks",
  "infested_stone",
  "infested_stone_bricks",
  "jigsaw",
  "light",
  "monster_spawner",
  "ominous_trial_spawner",
  "ominous_vault",
  "reinforced_deepslate",
  "repeating_command_block",
  "spawner",
  "structure_block",
  "structure_void",
  "trial_spawner",
  "vault",
])

const FINITE_SURVIVAL_BLOCKS = makeSet([
  ...ORE_BLOCKS,
  "ancient_debris",
  "calcite",
  "chiseled_cinnabar",
  "chiseled_tuff",
  "cinnabar",
  "cinnabar_bricks",
  "gilded_blackstone",
  "netherite_block",
  "polished_cinnabar",
  "polished_tuff",
  "raw_copper_block",
  "raw_gold_block",
  "raw_iron_block",
  "sponge",
  "tuff",
  "tuff_bricks",
  "wet_sponge",
])

const SILK_TOUCH_ONLY_PICKUP_BLOCKS = makeSet([
  ...ORE_BLOCKS,
  "blue_ice",
  "brain_coral_block",
  "brown_mushroom_block",
  "bee_nest",
  "bubble_coral_block",
  "crimson_nylium",
  "fire_coral_block",
  "grass_block",
  "horn_coral_block",
  "ice",
  "mycelium",
  "mushroom_stem",
  "packed_ice",
  "podzol",
  "red_mushroom_block",
  "sculk",
  "sculk_catalyst",
  "tube_coral_block",
  "warped_nylium",
])

const BULK_BUILDING_EXCLUDED_BLOCKS = makeSet([
  ...ORE_BLOCKS,
  "ancient_debris",
  "amethyst_block",
  "beacon",
  "blue_ice",
  "brain_coral_block",
  "brown_mushroom_block",
  "bubble_coral_block",
  "chorus_flower",
  "coal_block",
  "copper_block",
  "cut_copper",
  "diamond_block",
  "emerald_block",
  "exposed_copper",
  "exposed_cut_copper",
  "fire_coral_block",
  "froglight",
  "gilded_blackstone",
  "gold_block",
  "honey_block",
  "honeycomb_block",
  "horn_coral_block",
  "iron_block",
  "lapis_block",
  "lodestone",
  "magma_block",
  "netherite_block",
  "oxidized_copper",
  "oxidized_cut_copper",
  "packed_ice",
  "raw_copper_block",
  "raw_gold_block",
  "raw_iron_block",
  "red_mushroom_block",
  "redstone_block",
  "sculk",
  "sculk_catalyst",
  "sea_lantern",
  "slime_block",
  "sponge",
  "tube_coral_block",
  "waxed_copper_block",
  "waxed_cut_copper",
  "waxed_exposed_copper",
  "waxed_exposed_cut_copper",
  "waxed_oxidized_copper",
  "waxed_oxidized_cut_copper",
  "waxed_weathered_copper",
  "waxed_weathered_cut_copper",
  "weathered_copper",
  "weathered_cut_copper",
  "wet_sponge",
])

const BULK_BUILDING_EXACT_BLOCKS = makeSet([
  "andesite",
  "bamboo_block",
  "bamboo_mosaic",
  "barrel",
  "basalt",
  "bee_nest",
  "beehive",
  "blackstone",
  "bone_block",
  "bricks",
  "calcite",
  "chiseled_deepslate",
  "chiseled_nether_bricks",
  "chiseled_polished_blackstone",
  "chiseled_quartz_block",
  "chiseled_bookshelf",
  "chiseled_resin_bricks",
  "chiseled_red_sandstone",
  "chiseled_sandstone",
  "chiseled_stone_bricks",
  "chiseled_tuff",
  "clay",
  "coarse_dirt",
  "cobblestone",
  "cobbled_deepslate",
  "cracked_deepslate_bricks",
  "cracked_deepslate_tiles",
  "cracked_nether_bricks",
  "cracked_polished_blackstone_bricks",
  "cracked_stone_bricks",
  "crimson_nylium",
  "cut_red_sandstone",
  "cut_sandstone",
  "dark_prismarine",
  "deepslate",
  "deepslate_bricks",
  "deepslate_tiles",
  "diorite",
  "dirt",
  "dripstone_block",
  "dried_kelp_block",
  "end_stone",
  "end_stone_bricks",
  "glass",
  "granite",
  "gravel",
  "hay_block",
  "ice",
  "moss_block",
  "mossy_cobblestone",
  "mossy_stone_bricks",
  "mud",
  "mud_bricks",
  "mushroom_stem",
  "netherrack",
  "nether_bricks",
  "packed_mud",
  "pale_moss_block",
  "pillar_quartz_block",
  "polished_andesite",
  "polished_basalt",
  "polished_blackstone",
  "polished_blackstone_bricks",
  "polished_deepslate",
  "polished_diorite",
  "polished_granite",
  "polished_tuff",
  "prismarine",
  "prismarine_bricks",
  "purpur_block",
  "purpur_pillar",
  "quartz_block",
  "red_nether_bricks",
  "red_sand",
  "red_sandstone",
  "resin_block",
  "resin_bricks",
  "rooted_dirt",
  "sand",
  "sandstone",
  "smooth_basalt",
  "smooth_quartz",
  "smooth_red_sandstone",
  "smooth_sandstone",
  "smooth_stone",
  "snow_block",
  "stone",
  "stone_bricks",
  "stripped_bamboo_block",
  "sulfur",
  "sulfur_bricks",
  "tuff",
  "tuff_bricks",
  "warped_nylium",
])

const BULK_BUILDING_SUFFIXES = [
  "_concrete",
  "_concrete_powder",
  "_leaves",
  "_log",
  "_planks",
  "_stained_glass",
  "_stem",
  "_terracotta",
  "_wood",
  "_wool",
  "_hyphae",
]

export function isBlockGradientPresetId(value: unknown): value is BlockGradientPresetId {
  return typeof value === "string" && presetIds.has(value)
}

export function isBlockAllowedForPreset(
  block: BlockPresetBlock,
  presetId: BlockGradientPresetId,
) {
  switch (presetId) {
    case "survival":
      return isSurvivalObtainableBlock(block)
    case "renewable":
      return isSurvivalObtainableBlock(block) && !FINITE_SURVIVAL_BLOCKS.has(block.id)
    case "no_silk_touch":
      return isSurvivalObtainableBlock(block) && !SILK_TOUCH_ONLY_PICKUP_BLOCKS.has(block.id)
    case "bulk_building":
      return isBulkBuildingBlock(block)
    case "all":
    default:
      return true
  }
}

function isSurvivalObtainableBlock(block: BlockPresetBlock) {
  return !CREATIVE_ONLY_OR_INACCESSIBLE_BLOCKS.has(block.id)
}

function isBulkBuildingBlock(block: BlockPresetBlock) {
  const { id } = block

  if (!isSurvivalObtainableBlock(block) || BULK_BUILDING_EXCLUDED_BLOCKS.has(id)) {
    return false
  }

  if (BULK_BUILDING_EXACT_BLOCKS.has(id)) {
    return true
  }

  if (id.startsWith("stripped_")) {
    return true
  }

  return BULK_BUILDING_SUFFIXES.some((suffix) => id.endsWith(suffix))
}
