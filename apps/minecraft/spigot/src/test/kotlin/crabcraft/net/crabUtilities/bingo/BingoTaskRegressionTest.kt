package crabcraft.net.crabUtilities.bingo

/** Protects deployed task IDs and card positions used by stored bingo progress. */
object BingoTaskRegressionTest {
    private val EXPECTED_CARD_IDS =
        listOf(
            listOf(
                "grow_tree_in_nether",
                "play_five_goat_horns",
                "connect_all_ore_types",
                "activate_totem",
                "breed_mule",
                "kill_hostile_with_anvil",
                "two_creepers_one_boat",
                "ignite_campfire_from_distance",
                "breed_sniffers_collect_egg",
                "cure_zombie_villager",
                "gain_axolotl_regeneration",
                "equip_piglin_brute_axe",
                "kill_hostile_from_camel",
                "duplicate_allay",
                "collapse_scaffolding_tower",
                "breed_trusting_fox",
            ),
            listOf(
                "shear_bogged",
                "ring_bell_projectile",
                "berry_bush_kill",
                "dry_sponge_nether",
                "five_parrots_dance",
                "detonate_tnt_minecart",
                "target_opens_door",
                "collect_turtle_scute",
                "shelf_hotbar_swap",
                "remove_pig_saddle",
                "explorer_map_trade",
                "self_arrow_totem",
                "equip_piglin_gold_armour",
                "leashed_bee_sting",
                "creeper_rings_bell",
                "mine_copper_golem_statue",
            ),
            listOf(
                "sulfur_cube_tnt_ignite",
                "breed_third_colour_sheep",
                "unlock_ominous_vault",
                "fox_uses_totem",
                "tame_nautilus",
                "johnny_vindicator_kill",
                "hook_ghast",
                "water_bottle_extinguish_three",
                "shulker_bullet_duplicate",
                "warm_ridden_strider",
                "raid_bell_reveal_three",
                "piercing_arrow_hit_three",
                "leashed_frog_froglight",
                "charged_creeper_mob_head",
                "golden_dandelion_hoglin",
                "four_copper_trumpet_sounds",
            ),
            listOf(
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
            ),
            listOf(
                "build_ten_tall_dripleaf",
                "mob_equips_dropped_helmet",
                "clean_banner_pattern",
                "feed_panda_cake",
                "remove_enchantment_grindstone",
                "name_hoglin_zoglin",
                "lodestone_compass",
                "enderman_killed_by_endermites_only",
                "fill_chiseled_bookshelf_enchanted",
                "disarm_pillager",
                "hatch_thrown_chicken",
                "snow_every_height",
                "repair_iron_golem",
                "power_furnace_minecart",
                "player_end_crystal_hostile_kill",
                "wear_four_armour_materials",
            ),
            listOf(
                "hang_four_by_four_painting",
                "outline_hanging_sign",
                "fill_campfire_four_slots",
                "shoot_button_with_arrow",
                "fish_treasure_and_junk",
                "carpet_llama",
                "enchant_five_items",
                "throw_mending_book_in_lava",
                "stun_ravager",
                "fully_power_conduit",
                "poison_bee",
                "place_fish_in_nether",
                "named_ghast_overworld",
                "fill_ender_chest",
                "apply_armour_trim",
                "four_sherd_decorated_pot",
            ),
        )

    @JvmStatic
    fun main(args: Array<String>) {
        val cards =
            listOf(
                BingoTask.cardOne(),
                BingoTask.cardTwo(),
                BingoTask.cardThree(),
                BingoTask.cardFour(),
                BingoTask.cardFive(),
                BingoTask.cardSix(),
            )
        val combined = HashSet<BingoTask>()
        for ((index, card) in cards.withIndex()) {
            check(
                card.map { it.id() } == EXPECTED_CARD_IDS[index],
                "Bingo #${index + 1} detector IDs or positions changed",
            )
            for (task in card) {
                check(combined.add(task), "A task appears on more than one card: ${task.id()}")
                check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task is not resolvable: ${task.id()}")
            }
        }
        val deployed = BingoTask.allDeployed()
        check(
            deployed.size == combined.size && combined == deployed.toHashSet(),
            "Card catalogues do not cover every deployed task exactly once",
        )
        check(
            combined == BingoTask.values().toSet(),
            "A BingoTask enum value is missing from the deployed catalogue",
        )
        check(BingoTask.fromId("leash_rabbit").isEmpty, "Retired card task is still advertised")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
