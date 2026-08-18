package crabcraft.net.crabUtilities.bingo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dependency-free regression checks for every deployed weekly bingo catalogue. */
public final class BingoTaskRegressionTest {
    private BingoTaskRegressionTest() {}

    public static void main(String[] args) {
        cardOneOrderIsStable();
        cardTwoOrderIsStable();
        cardThreeOrderIsStable();
        deployedCatalogueIsCompleteAndResolvable();
        cardCataloguesAreDisjointAndImmutable();
        retiredTasksRemainUnsupported();
    }

    private static void cardOneOrderIsStable() {
        List<BingoTask> expected = List.of(
                BingoTask.GROW_TREE_IN_NETHER,
                BingoTask.PLAY_FIVE_GOAT_HORNS,
                BingoTask.CONNECT_ALL_ORE_TYPES,
                BingoTask.ACTIVATE_TOTEM,
                BingoTask.BREED_MULE,
                BingoTask.KILL_HOSTILE_WITH_ANVIL,
                BingoTask.TWO_CREEPERS_ONE_BOAT,
                BingoTask.IGNITE_CAMPFIRE_FROM_DISTANCE,
                BingoTask.BREED_SNIFFERS_COLLECT_EGG,
                BingoTask.CURE_ZOMBIE_VILLAGER,
                BingoTask.GAIN_AXOLOTL_REGENERATION,
                BingoTask.EQUIP_PIGLIN_BRUTE_AXE,
                BingoTask.KILL_HOSTILE_FROM_CAMEL,
                BingoTask.DUPLICATE_ALLAY,
                BingoTask.COLLAPSE_SCAFFOLDING_TOWER,
                BingoTask.BREED_TRUSTING_FOX);
        check(BingoTask.cardOne().equals(expected), "Bingo #1 order changed");
        check(BingoTask.cardOne().size() == 16, "Bingo #1 must contain 16 tasks");
        check(idsOf(BingoTask.cardOne()).equals(List.of(
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
                        "breed_trusting_fox")),
                "Bingo #1 detector IDs changed");
    }

    private static void cardTwoOrderIsStable() {
        List<BingoTask> expected = List.of(
                BingoTask.SHEAR_BOGGED,
                BingoTask.RING_BELL_PROJECTILE,
                BingoTask.BERRY_BUSH_KILL,
                BingoTask.DRY_SPONGE_NETHER,
                BingoTask.FIVE_PARROTS_DANCE,
                BingoTask.DETONATE_TNT_MINECART,
                BingoTask.TARGET_OPENS_DOOR,
                BingoTask.COLLECT_TURTLE_SCUTE,
                BingoTask.SHELF_HOTBAR_SWAP,
                BingoTask.REMOVE_PIG_SADDLE,
                BingoTask.EXPLORER_MAP_TRADE,
                BingoTask.SELF_ARROW_TOTEM,
                BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR,
                BingoTask.LEASHED_BEE_STING,
                BingoTask.CREEPER_RINGS_BELL,
                BingoTask.MINE_COPPER_GOLEM_STATUE);
        check(BingoTask.cardTwo().equals(expected), "Bingo #2 order changed");
        check(BingoTask.cardTwo().size() == 16, "Bingo #2 must contain 16 tasks");
        check(idsOf(BingoTask.cardTwo()).equals(List.of(
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
                        "mine_copper_golem_statue")),
                "Bingo #2 detector IDs changed");
    }

    private static void cardThreeOrderIsStable() {
        List<BingoTask> expected = List.of(
                BingoTask.SULFUR_CUBE_TNT_IGNITE,
                BingoTask.BREED_THIRD_COLOUR_SHEEP,
                BingoTask.UNLOCK_OMINOUS_VAULT,
                BingoTask.FOX_USES_TOTEM,
                BingoTask.TAME_NAUTILUS,
                BingoTask.JOHNNY_VINDICATOR_KILL,
                BingoTask.HOOK_GHAST,
                BingoTask.WATER_BOTTLE_EXTINGUISH_THREE,
                BingoTask.SHULKER_BULLET_DUPLICATE,
                BingoTask.WARM_RIDDEN_STRIDER,
                BingoTask.RAID_BELL_REVEAL_THREE,
                BingoTask.PIERCING_ARROW_HIT_THREE,
                BingoTask.LEASHED_FROG_FROGLIGHT,
                BingoTask.CHARGED_CREEPER_MOB_HEAD,
                BingoTask.GOLDEN_DANDELION_HOGLIN,
                BingoTask.FOUR_COPPER_TRUMPET_SOUNDS);
        check(BingoTask.cardThree().equals(expected), "Bingo #3 order changed");
        check(BingoTask.cardThree().size() == 16, "Bingo #3 must contain 16 tasks");
        check(idsOf(BingoTask.cardThree()).equals(List.of(
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
                        "four_copper_trumpet_sounds")),
                "Bingo #3 detector IDs changed");
    }

    private static void deployedCatalogueIsCompleteAndResolvable() {
        List<BingoTask> deployed = BingoTask.allDeployed();
        check(deployed.equals(List.of(BingoTask.values())),
                "Deployed catalogue does not contain every BingoTask enum value in declaration order");
        check(deployed.size() == 48, "Expected 48 deployed bingo tasks");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : deployed) {
            check(!task.id().isBlank(), "Blank task ID");
            check(ids.add(task.id()), "Duplicate task ID: " + task.id());
            check(!task.description().isBlank(), "Blank description: " + task.id());
            check(!task.detail().isBlank(), "Blank detail: " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task,
                    "Task is not resolvable: " + task.id());
        }
    }

    private static void cardCataloguesAreDisjointAndImmutable() {
        Set<BingoTask> cardOne = new HashSet<>(BingoTask.cardOne());
        Set<BingoTask> cardTwo = new HashSet<>(BingoTask.cardTwo());
        Set<BingoTask> cardThree = new HashSet<>(BingoTask.cardThree());
        check(cardOne.size() == 16, "Bingo #1 contains a duplicate task");
        check(cardTwo.size() == 16, "Bingo #2 contains a duplicate task");
        check(cardThree.size() == 16, "Bingo #3 contains a duplicate task");
        check(cardOne.stream().noneMatch(cardTwo::contains), "A task appears on both deployed cards");
        check(cardOne.stream().noneMatch(cardThree::contains), "A task appears on Cards #1 and #3");
        check(cardTwo.stream().noneMatch(cardThree::contains), "A task appears on Cards #2 and #3");

        Set<BingoTask> combined = new HashSet<>(cardOne);
        combined.addAll(cardTwo);
        combined.addAll(cardThree);
        check(combined.equals(new HashSet<>(BingoTask.allDeployed())),
                "Card catalogues do not cover every deployed task exactly once");

        checkUnmodifiable(BingoTask.cardOne(), "Bingo #1 catalogue is mutable");
        checkUnmodifiable(BingoTask.cardTwo(), "Bingo #2 catalogue is mutable");
        checkUnmodifiable(BingoTask.cardThree(), "Bingo #3 catalogue is mutable");
        checkUnmodifiable(BingoTask.allDeployed(), "Deployed catalogue is mutable");
    }

    private static void retiredTasksRemainUnsupported() {
        check(BingoTask.fromId("leash_rabbit").isEmpty(), "Retired card task is still advertised");
    }

    private static List<String> idsOf(List<BingoTask> tasks) {
        return tasks.stream().map(BingoTask::id).toList();
    }

    private static void checkUnmodifiable(List<BingoTask> tasks, String message) {
        try {
            tasks.add(BingoTask.SHEAR_BOGGED);
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected: public task catalogues are immutable snapshots.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
