/**
 * Download advancement icons from the Minecraft Wiki.
 * URL pattern: https://minecraft.wiki/images/Invicon_{ItemName}.png
 *
 * Usage: bun run scripts/download-advancement-icons.ts
 */

import { existsSync, mkdirSync, writeFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const OUT_DIR = resolve(__dirname, "..", "public", "advancements", "icons");

// Map advancement ID → Minecraft Wiki Invicon name
const ICON_MAP: Record<string, string> = {
  // Story
  "minecraft:story/root": "Grass_Block",
  "minecraft:story/mine_stone": "Wooden_Pickaxe",
  "minecraft:story/upgrade_tools": "Stone_Pickaxe",
  "minecraft:story/smelt_iron": "Iron_Ingot",
  "minecraft:story/obtain_armor": "Iron_Chestplate",
  "minecraft:story/lava_bucket": "Lava_Bucket",
  "minecraft:story/iron_tools": "Iron_Pickaxe",
  "minecraft:story/deflect_arrow": "Shield",
  "minecraft:story/form_obsidian": "Obsidian",
  "minecraft:story/mine_diamond": "Diamond",
  "minecraft:story/enter_the_nether": "Flint_and_Steel",
  "minecraft:story/shiny_gear": "Diamond_Chestplate",
  "minecraft:story/enchant_item": "Enchanting_Table",
  "minecraft:story/cure_zombie_villager": "Golden_Apple",
  "minecraft:story/follow_ender_eye": "Eye_of_Ender",
  "minecraft:story/enter_the_end": "End_Stone",

  // Nether
  "minecraft:nether/root": "Red_Nether_Bricks",
  "minecraft:nether/return_to_sender": "Fire_Charge",
  "minecraft:nether/find_bastion": "Polished_Blackstone_Bricks",
  "minecraft:nether/obtain_ancient_debris": "Ancient_Debris",
  "minecraft:nether/fast_travel": "Map",
  "minecraft:nether/find_fortress": "Nether_Bricks",
  "minecraft:nether/obtain_crying_obsidian": "Crying_Obsidian",
  "minecraft:nether/distract_piglin": "Gold_Ingot",
  "minecraft:nether/ride_strider": "Warped_Fungus_on_a_Stick",
  "minecraft:nether/uneasy_alliance": "Ghast_Tear",
  "minecraft:nether/loot_bastion": "Chest",
  "minecraft:nether/netherite_armor": "Netherite_Chestplate",
  "minecraft:nether/get_wither_skull": "Wither_Skeleton_Skull",
  "minecraft:nether/obtain_blaze_rod": "Blaze_Rod",
  "minecraft:nether/charge_respawn_anchor": "Respawn_Anchor",
  "minecraft:nether/ride_strider_in_overworld_lava": "Warped_Fungus_on_a_Stick",
  "minecraft:nether/explore_nether": "Netherite_Boots",
  "minecraft:nether/summon_wither": "Wither_Skeleton_Skull",
  "minecraft:nether/brew_potion": "Brewing_Stand",
  "minecraft:nether/create_beacon": "Beacon",
  "minecraft:nether/all_potions": "Milk_Bucket",
  "minecraft:nether/create_full_beacon": "Beacon",
  "minecraft:nether/all_effects": "Bucket",

  // End
  "minecraft:end/root": "End_Stone",
  "minecraft:end/kill_dragon": "Dragon_Head",
  "minecraft:end/dragon_egg": "Dragon_Egg",
  "minecraft:end/enter_end_gateway": "Ender_Pearl",
  "minecraft:end/respawn_dragon": "End_Stone",
  "minecraft:end/dragon_breath": "Dragon%27s_Breath",
  "minecraft:end/find_end_city": "Purpur_Block",
  "minecraft:end/elytra": "Elytra",
  "minecraft:end/levitate": "Shulker_Shell",

  // Adventure
  "minecraft:adventure/root": "Map",
  "minecraft:adventure/voluntary_exile": "Ominous_Bottle",
  "minecraft:adventure/use_lodestone": "Lodestone",
  "minecraft:adventure/spyglass_at_parrot": "Spyglass",
  "minecraft:adventure/kill_a_mob": "Iron_Sword",
  "minecraft:adventure/read_power_of_chiseled_bookshelf": "Chiseled_Bookshelf",
  "minecraft:adventure/trade": "Emerald",
  "minecraft:adventure/trim_with_any_armor_pattern": "Netherite_Chestplate",
  "minecraft:adventure/honey_block_slide": "Honey_Block",
  "minecraft:adventure/ol_betsy": "Crossbow",
  "minecraft:adventure/lightning_rod_with_villager_no_fire": "Lightning_Rod",
  "minecraft:adventure/fall_from_world_height": "Water_Bucket",
  "minecraft:adventure/salvage_sherd": "Angler_Pottery_Sherd",
  "minecraft:adventure/avoid_vibration": "Leather_Boots",
  "minecraft:adventure/sleep_in_bed": "Red_Bed",
  "minecraft:adventure/hero_of_the_village": "Ominous_Banner",
  "minecraft:adventure/spyglass_at_ghast": "Spyglass",
  "minecraft:adventure/throw_trident": "Trident",
  "minecraft:adventure/kill_mob_near_sculk_catalyst": "Sculk_Catalyst",
  "minecraft:adventure/shoot_arrow": "Bow",
  "minecraft:adventure/kill_all_mobs": "Diamond_Sword",
  "minecraft:adventure/totem_of_undying": "Totem_of_Undying",
  "minecraft:adventure/summon_iron_golem": "Carved_Pumpkin",
  "minecraft:adventure/trade_at_world_height": "Emerald",
  "minecraft:adventure/trim_with_all_exclusive_armor_patterns": "Netherite_Chestplate",
  "minecraft:adventure/two_birds_one_arrow": "Crossbow",
  "minecraft:adventure/whos_the_pillager_now": "Crossbow",
  "minecraft:adventure/arbalistic": "Crossbow",
  "minecraft:adventure/craft_decorated_pot_using_only_sherds": "Decorated_Pot",
  "minecraft:adventure/adventuring_time": "Diamond_Boots",
  "minecraft:adventure/play_jukebox_in_meadows": "Jukebox",
  "minecraft:adventure/walk_on_powder_snow_with_leather_boots": "Leather_Boots",
  "minecraft:adventure/spyglass_at_dragon": "Spyglass",
  "minecraft:adventure/very_very_frightening": "Trident",
  "minecraft:adventure/sniper_duel": "Arrow",
  "minecraft:adventure/bullseye": "Target",
  "minecraft:adventure/brush_armadillo": "Brush",
  "minecraft:adventure/minecraft_trials_edition": "Copper_Bulb",
  "minecraft:adventure/crafters_crafting_crafters": "Crafter",
  "minecraft:adventure/lighten_up": "Copper_Bulb",
  "minecraft:adventure/who_needs_rockets": "Wind_Charge",
  "minecraft:adventure/under_lock_and_key": "Vault",
  "minecraft:adventure/revaulting": "Ominous_Trial_Key",
  "minecraft:adventure/blowback": "Wind_Charge",
  "minecraft:adventure/overoverkill": "Mace",
  "minecraft:adventure/heart_transplanter": "Creaking_Heart",
  "minecraft:adventure/spear_many_mobs": "Trident",

  // Husbandry
  "minecraft:husbandry/root": "Wheat",
  "minecraft:husbandry/safely_harvest_honey": "Honey_Bottle",
  "minecraft:husbandry/breed_an_animal": "Wheat",
  "minecraft:husbandry/allay_deliver_item_to_player": "Cookie",
  "minecraft:husbandry/ride_a_boat_with_a_goat": "Oak_Boat",
  "minecraft:husbandry/tame_an_animal": "Lead",
  "minecraft:husbandry/make_a_sign_glow": "Glow_Ink_Sac",
  "minecraft:husbandry/fishy_business": "Fishing_Rod",
  "minecraft:husbandry/silk_touch_nest": "Bee_Nest",
  "minecraft:husbandry/tadpole_in_a_bucket": "Bucket",
  "minecraft:husbandry/obtain_sniffer_egg": "Sniffer_Egg",
  "minecraft:husbandry/plant_seed": "Wheat_Seeds",
  "minecraft:husbandry/wax_on": "Honeycomb",
  "minecraft:husbandry/wax_off": "Stone_Axe",
  "minecraft:husbandry/bred_all_animals": "Golden_Carrot",
  "minecraft:husbandry/allay_deliver_cake_to_note_block": "Note_Block",
  "minecraft:husbandry/complete_catalogue": "Raw_Cod",
  "minecraft:husbandry/tactical_fishing": "Pufferfish",
  "minecraft:husbandry/leash_all_frog_variants": "Lead",
  "minecraft:husbandry/feed_snifflet": "Torchflower_Seeds",
  "minecraft:husbandry/balanced_diet": "Apple",
  "minecraft:husbandry/plant_any_sniffer_seed": "Torchflower_Seeds",
  "minecraft:husbandry/obtain_netherite_hoe": "Netherite_Hoe",
  "minecraft:husbandry/axolotl_in_a_bucket": "Bucket",
  "minecraft:husbandry/kill_axolotl_target": "Diamond_Sword",
  "minecraft:husbandry/froglights": "Verdant_Froglight",
  "minecraft:husbandry/remove_wolf_armor": "Shears",
  "minecraft:husbandry/repair_wolf_armor": "Armadillo_Scute",
  "minecraft:husbandry/whole_pack": "Bone",
  "minecraft:husbandry/place_dried_ghast_in_water": "Dried_Ghast",
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
    const [advId, wikiName] = entries[i];
    const filename = advId.replace("minecraft:", "").replace("/", "_");
    const outPath = resolve(OUT_DIR, `${filename}.png`);

    if (existsSync(outPath)) {
      skipped++;
      continue;
    }

    const url = `${WIKI_BASE}${wikiName}.png`;
    try {
      const res = await fetch(url, {
        headers: { "User-Agent": "CrabCraft-AdvancementIconDownloader/1.0" },
      });
      if (!res.ok) {
        console.warn(`  FAILED (${res.status}): ${advId} → ${wikiName}`);
        failed++;
        failures.push(`${advId} → ${wikiName} (${res.status})`);
        continue;
      }

      const buffer = Buffer.from(await res.arrayBuffer());
      writeFileSync(outPath, buffer);
      downloaded++;
    } catch (e: any) {
      console.warn(`  ERROR: ${advId} → ${wikiName}: ${e.message}`);
      failed++;
      failures.push(`${advId} → ${wikiName} (${e.message})`);
    }

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
