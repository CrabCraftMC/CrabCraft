/**
 * Download award icons from the Minecraft Wiki.
 * URL pattern: https://minecraft.wiki/images/Invicon_{ItemName}.png
 *
 * Usage: bun run scripts/download-award-icons.ts
 */

import { existsSync, mkdirSync, writeFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const OUT_DIR = resolve(__dirname, "..", "public", "awards", "icons");

// Map award ID → Minecraft Wiki Invicon name
const ICON_MAP: Record<string, string> = {
  // Movement
  aviate: "Elytra",
  climb: "Ladder",
  crouch: "Leather_Boots",
  dive: "Heart_of_the_Sea",
  fall: "Feather",
  jump: "Slime_Ball",
  sprint: "Golden_Boots",
  swim: "Turtle_Shell",
  walk: "Diamond_Boots",
  walk_on_water: "Frost_Walker_Boots",
  ride_boat: "Oak_Boat",
  ride_horse: "Saddle",
  ride_minecart: "Minecart",
  ride_pig: "Carrot_on_a_Stick",
  ride_strider: "Warped_Fungus_on_a_Stick",
  ride_happy_ghast: "White_Harness",
  ride_nautilus: "Nautilus_Shell",

  // Mining
  mine_amethyst: "Amethyst_Shard",
  mine_ancient_debris: "Ancient_Debris",
  mine_coal_ore: "Coal",
  mine_cobweb: "Cobweb",
  mine_copper_ore: "Raw_Copper",
  mine_coral: "Brain_Coral",
  mine_diamond_ore: "Diamond",
  mine_dimensional: "Netherrack",
  mine_emerald_ore: "Emerald",
  mine_glass: "Glass",
  mine_gold_ore: "Raw_Gold",
  mine_grass: "Short_Grass",
  mine_ground: "Dirt",
  mine_ice: "Ice",
  mine_iron_ore: "Raw_Iron",
  mine_kelp: "Kelp",
  mine_lapis_ore: "Lapis_Lazuli",
  mine_nether_foliage: "Nether_Sprouts",
  mine_nether_quartz_ore: "Nether_Quartz",
  mine_obsidian: "Obsidian",
  mine_pointed_dripstone: "Pointed_Dripstone",
  mine_redstone_ore: "Redstone_Dust",
  mine_snow: "Snowball",
  mine_spawner: "Spawner",
  mine_stone: "Stone",
  mine_wood: "Oak_Log",

  // Combat
  damage_dealt: "Diamond_Sword",
  damage_shield: "Shield",
  damage_taken: "Golden_Apple",
  death: "Skeleton_Skull",
  kill_any: "Iron_Sword",
  kill_allay: "Allay_Spawn_Egg",
  kill_axolotl: "Axolotl_Bucket",
  kill_bat: "Bat_Spawn_Egg",
  kill_bee: "Honeycomb",
  kill_blaze: "Blaze_Rod",
  kill_camel: "Camel_Spawn_Egg",
  kill_chicken: "Chicken",
  kill_cow: "Beef",
  kill_creeper: "Creeper_Head",
  kill_dolphin: "Dolphin_Spawn_Egg",
  kill_ender_dragon: "Dragon_Head",
  kill_enderman: "Ender_Pearl",
  kill_endermite: "Endermite_Spawn_Egg",
  kill_fish: "Tropical_Fish",
  kill_fox: "Fox_Spawn_Egg",
  kill_frog: "Frog_Spawn_Egg",
  kill_ghast: "Ghast_Tear",
  kill_goat: "Goat_Spawn_Egg",
  kill_guardian: "Prismarine_Shard",
  kill_hoglins: "Hoglin_Spawn_Egg",
  kill_horse: "Horse_Spawn_Egg",
  kill_illagers: "Pillager_Spawn_Egg",
  kill_iron_golem: "Iron_Ingot",
  kill_llama: "Llama_Spawn_Egg",
  kill_magma_cube: "Magma_Cream",
  kill_sulfur_cube: "Sulfur_Cube_Spawn_Egg",
  kill_mooshroom: "Mooshroom_Spawn_Egg",
  kill_ocelot: "Ocelot_Spawn_Egg",
  kill_panda: "Panda_Spawn_Egg",
  kill_parrot: "Parrot_Spawn_Egg",
  kill_phantom: "Phantom_Membrane",
  kill_pig: "Porkchop",
  kill_piglin: "Piglin_Spawn_Egg",
  kill_piglin_brute: "Piglin_Brute_Spawn_Egg",
  kill_polar_bear: "Polar_Bear_Spawn_Egg",
  kill_rabbit: "Rabbit_Foot",
  kill_ravager: "Ravager_Spawn_Egg",
  kill_sheep: "White_Wool",
  kill_shulker: "Shulker_Shell",
  kill_silverfish: "Silverfish_Spawn_Egg",
  kill_skeleton: "Bone",
  kill_slime: "Slime_Block",
  kill_sniffer: "Sniffer_Spawn_Egg",
  kill_snow_golem: "Carved_Pumpkin",
  kill_spider: "Spider_Eye",
  kill_squid: "Ink_Sac",
  kill_strider: "Strider_Spawn_Egg",
  kill_tadpole: "Tadpole_Bucket",
  kill_turtle: "Turtle_Egg",
  kill_vex: "Vex_Spawn_Egg",
  kill_villager: "Villager_Spawn_Egg",
  kill_wandering_trader: "Wandering_Trader_Spawn_Egg",
  kill_warden: "Sculk_Shrieker",
  kill_witch: "Witch_Spawn_Egg",
  kill_wither_skeleton: "Wither_Skeleton_Skull",
  kill_wolf: "Wolf_Spawn_Egg",
  kill_zombie: "Zombie_Head",
  kill_zombified_piglin: "Zombified_Piglin_Spawn_Egg",
  killed_by_creeper: "TNT",
  killed_by_warden: "Sculk_Catalyst",
  kill_breeze: "Breeze_Rod",
  kill_bogged: "Bogged_Spawn_Egg",
  kill_armadillo: "Armadillo_Spawn_Egg",
  kill_creaking: "Creaking_Heart",
  kill_happy_ghast: "Happy_Ghast_Spawn_Egg",
  kill_parched: "Parched_Spawn_Egg",
  kill_camel_husk: "Camel_Husk_Spawn_Egg",
  killed_by_breeze: "Wind_Charge",
  killed_by_bogged: "Arrow_of_Poison",

  // Crafting
  craft_armor: "Diamond_Chestplate",
  craft_beacon: "Beacon",
  craft_bookshelf: "Bookshelf",
  craft_bread: "Bread",
  craft_bundle: "Bundle",
  craft_clock: "Clock",
  craft_compass: "Compass",
  craft_dye: "Red_Dye",
  craft_ender_chest: "Ender_Chest",
  craft_glowstone: "Glowstone",
  craft_mineral_block: "Diamond_Block",
  craft_paper: "Paper",
  craft_recovery_compass: "Recovery_Compass",
  craft_respawn_anchor: "Respawn_Anchor",
  craft_sponge: "Sponge",
  craft_spyglass: "Spyglass",
  craft_sword: "Netherite_Sword",
  craft_tnt: "TNT",
  craft_tools: "Iron_Pickaxe",
  craft_turtle_helmet: "Turtle_Shell",
  craft_wool: "White_Carpet",
  craft_mace: "Mace",
  craft_spear: "Stone_Spear",
  craft_harness: "White_Harness",
  craft_saddle: "Saddle",
  craft_copper_bulb: "Copper_Bulb",
  craft_crafter: "Crafter",

  // Items / Interactions
  break_tools: "Wooden_Pickaxe",
  brush_suspicious: "Brush",
  drink_milk: "Milk_Bucket",
  drop: "Dropper",
  enchant: "Enchanting_Table",
  interact_anvil: "Anvil",
  interact_blast_furnace: "Blast_Furnace",
  interact_brewing_stand: "Brewing_Stand",
  interact_campfire: "Campfire",
  interact_cartography: "Cartography_Table",
  interact_grindstone: "Grindstone",
  interact_lectern: "Lectern",
  interact_loom: "Loom",
  interact_smoker: "Smoker",
  interact_stonecutter: "Stonecutter",
  interact_crafting: "Crafting_Table",
  interact_furnace: "Furnace",
  interact_smithing: "Smithing_Table",
  interact_beacon: "Beacon",
  noteblock: "Note_Block",
  open_container: "Chest",
  open_chest: "Chest",
  open_barrel: "Barrel",
  open_shulker_box: "Shulker_Box",
  open_ender_chest: "Ender_Chest",
  play_record: "Music_Disc_Cat",
  pot_flower: "Flower_Pot",
  ring_bell: "Bell",
  target_hit: "Target",
  use_book: "Book_and_Quill",
  use_bow: "Bow",
  use_crossbow: "Crossbow",
  use_dirt: "Dirt",
  use_egg: "Egg",
  use_ender_eye: "Ender_Eye",
  use_ender_pearl: "Ender_Pearl",
  use_fireworks: "Firework_Rocket",
  use_flint: "Flint_and_Steel",
  use_goat_horn: "Goat_Horn",
  use_hoe: "Diamond_Hoe",
  use_honey_bottle: "Honey_Bottle",
  use_lava_bucket: "Lava_Bucket",
  use_potion: "Potion",
  use_shears: "Shears",
  use_snowball: "Snowball",
  use_totem: "Totem_of_Undying",
  use_water_bucket: "Water_Bucket",
  use_mace: "Mace",
  use_wind_charge: "Wind_Charge",
  use_spear: "Iron_Spear",

  // Food
  collect_berries: "Sweet_Berries",
  collect_shroom: "Red_Mushroom",
  eat_cookie: "Cookie",
  eat_fish: "Cooked_Cod",
  eat_junkfood: "Poisonous_Potato",
  eat_meat: "Cooked_Beef",
  eat_rawmeat: "Raw_Beef",
  eat_soup: "Mushroom_Stew",
  eat_veggie: "Carrot",
  eat_cake: "Cake",
  harvest_bamboo: "Bamboo",
  harvest_nether_wart: "Nether_Wart",
  harvest_sugar: "Sugar_Cane",

  // Building
  place_banner: "White_Banner",
  place_bars: "Iron_Bars",
  place_cactus: "Cactus",
  place_candle: "Candle",
  place_chorus_flower: "Chorus_Flower",
  place_conveyor: "Hopper",
  place_electrics: "Redstone_Dust",
  place_glass: "Glass",
  place_lantern: "Lantern",
  place_lightning_rod: "Lightning_Rod",
  place_lodestone: "Lodestone",
  place_piston: "Piston",
  place_rails: "Rail",
  place_sapling: "Oak_Sapling",
  place_scaffolding: "Scaffolding",
  place_sign: "Oak_Sign",
  place_stairs: "Oak_Stairs",
  place_torch: "Torch",
  place_wall: "Stone_Brick_Wall",

  // Misc
  biomes: "Filled_Map",
  breed: "Wheat",
  play: "Clock",
  sleep: "Red_Bed",
  time_since_death: "Totem_of_Undying",
  time_since_sleep: "Phantom_Membrane",
  trade: "Emerald",
  trigger_raid: "Ominous_Bottle",
  win_raid: "Emerald_Block",
  talk_villager: "Villager_Spawn_Egg",
  sneak_time: "Leather_Boots",
  fish_caught: "Fishing_Rod",
};

const WIKI_BASE = "https://minecraft.wiki/images/Invicon_";

async function main() {
  mkdirSync(OUT_DIR, { recursive: true });

  const entries = Object.entries(ICON_MAP);
  let downloaded = 0;
  let skipped = 0;
  let failed = 0;
  const failures: string[] = [];

  for (let i = 0; i < entries.length; i++) {
    const [awardId, wikiName] = entries[i];
    const outPath = resolve(OUT_DIR, `${awardId}.png`);

    // Force re-download all icons

    const url = `${WIKI_BASE}${wikiName}.png`;
    try {
      const res = await fetch(url, {
        headers: { "User-Agent": "CrabCraft-AwardIconDownloader/1.0" },
      });
      if (!res.ok) {
        console.warn(`  FAILED (${res.status}): ${awardId} → ${wikiName}`);
        failed++;
        failures.push(`${awardId} → ${wikiName} (${res.status})`);
        continue;
      }

      const buffer = Buffer.from(await res.arrayBuffer());
      writeFileSync(outPath, buffer);
      downloaded++;
    } catch (e: any) {
      console.warn(`  ERROR: ${awardId} → ${wikiName}: ${e.message}`);
      failed++;
      failures.push(`${awardId} → ${wikiName} (${e.message})`);
    }

    // Rate limit: small delay between requests
    if (i % 10 === 9) {
      process.stdout.write(`\r  ${i + 1}/${entries.length} processed`);
      await new Promise((r) => setTimeout(r, 200));
    }
  }

  console.log(`\n\nDone! ${downloaded} downloaded, ${skipped} already existed, ${failed} failed.`);
  if (failures.length > 0) {
    console.log("\nFailed downloads:");
    for (const f of failures) console.log(`  - ${f}`);
  }
}

main();
