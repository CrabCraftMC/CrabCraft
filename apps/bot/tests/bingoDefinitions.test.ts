import { describe, expect, test } from "bun:test";
import {
  FOURTH_BINGO_CARD,
  PREPARED_BINGO_CARDS,
  SUPPORTED_BINGO_TASK_IDS,
} from "../src/utils/bingoDefinitions.js";

describe("prepared bingo cards", () => {
  test("prepares Bingo #4 for the next weekly window", () => {
    expect(FOURTH_BINGO_CARD.number).toBe(4);
    expect(FOURTH_BINGO_CARD.startsAt).toBe(1_787_558_400);
    expect(FOURTH_BINGO_CARD.endsAt).toBe(1_788_130_800);
    expect(FOURTH_BINGO_CARD.tasks).toHaveLength(16);
    expect(new Set(FOURTH_BINGO_CARD.tasks.map((task) => task.id)).size).toBe(16);
    expect(FOURTH_BINGO_CARD.tasks.map((task) => task.id)).toEqual([
      "sulfur_cube_diamond_bucket",
      "snow_golem_kills_blaze",
      "four_by_four_nether_portal",
      "crossbow_firework_kill_two",
      "happy_ghast_hostile_boat",
      "spear_hit_three",
      "silverfish_hide_in_stone",
      "tree_with_bee_nest",
      "brown_mooshroom_wither_stew",
      "piston_push_twelve",
      "ender_pearl_teleport_hundred",
      "reflected_breeze_wind_charge",
      "projectile_smash_filled_pot",
      "cure_poison_honey_bottle",
      "freeze_skeleton_stray",
      "sculk_catalyst_player_kill",
    ]);
  });

  test("advertises every prepared task as supported", () => {
    const preparedTaskIds = PREPARED_BINGO_CARDS.flatMap((card) =>
      card.tasks.map((task) => task.id),
    );

    expect(PREPARED_BINGO_CARDS.map((card) => card.number)).toEqual([1, 2, 3, 4]);
    expect(SUPPORTED_BINGO_TASK_IDS).toEqual(new Set(preparedTaskIds));
  });
});
