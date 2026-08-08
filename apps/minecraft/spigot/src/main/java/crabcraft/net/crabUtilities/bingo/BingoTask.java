package crabcraft.net.crabUtilities.bingo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stable identifiers for deployed bingo task detectors. */
public enum BingoTask {
    GROW_TREE_IN_NETHER(
            "grow_tree_in_nether",
            "Grow a tree in the Nether.",
            "Use bone meal to grow a sapling or fungus while you are in the Nether."),
    PLAY_FIVE_GOAT_HORNS(
            "play_five_goat_horns",
            "Play five different goat horn types.",
            "Successfully play five distinct goat horn instruments after this card starts."),
    CONNECT_ALL_ORE_TYPES(
            "connect_all_ore_types",
            "Place all 10 ore families plus Ancient Debris together (normal/deepslate count as one).",
            "Place coal, copper, iron, gold, redstone, lapis, diamond, emerald, Nether gold, Nether quartz and Ancient Debris. Every block must be placed by you after the card starts and touch the cluster by a face. Stone and deepslate variants count as the same ore family and do not count twice."),
    ACTIVATE_TOTEM(
            "activate_totem",
            "Activate a Totem of Undying.",
            "Survive fatal damage by holding the totem in either hand."),
    BREED_MULE(
            "breed_mule",
            "Breed a horse and donkey to produce a mule.",
            "You must be the breeder credited when a horse and donkey produce the mule."),
    KILL_HOSTILE_WITH_ANVIL(
            "kill_hostile_with_anvil",
            "Kill a hostile mob with a falling anvil.",
            "Place the anvil yourself and let its falling-block damage deal the final blow."),
    TWO_CREEPERS_ONE_BOAT(
            "two_creepers_one_boat",
            "Place a boat and get two creepers into it.",
            "You receive credit as the player who placed the boat after the card starts; it must then carry two creepers at the same time."),
    IGNITE_CAMPFIRE_FROM_DISTANCE(
            "ignite_campfire_from_distance",
            "Light a campfire with a flaming projectile from at least 10 blocks away.",
            "The projectile must be launched by you while on fire and travel at least ten blocks before lighting the campfire."),
    BREED_SNIFFERS_COLLECT_EGG(
            "breed_sniffers_collect_egg",
            "Breed two Sniffers and collect their Sniffer Egg.",
            "Breed the Sniffers yourself, then personally pick up the egg they produce."),
    CURE_ZOMBIE_VILLAGER(
            "cure_zombie_villager",
            "Cure a zombie villager.",
            "You must be the player recorded by Minecraft as starting the successful cure."),
    GAIN_AXOLOTL_REGENERATION(
            "gain_axolotl_regeneration",
            "Gain Regeneration from an axolotl.",
            "Receive the Regeneration effect whose Paper event cause is an axolotl."),
    EQUIP_PIGLIN_BRUTE_AXE(
            "equip_piglin_brute_axe",
            "Give a Piglin Brute an enchanted golden axe to equip.",
            "Drop an enchanted golden axe that a Piglin Brute chooses to pick up and equip."),
    KILL_HOSTILE_FROM_CAMEL(
            "kill_hostile_from_camel",
            "Kill a hostile mob while riding a camel.",
            "You must still be mounted on the camel when your hostile-mob kill is recorded."),
    DUPLICATE_ALLAY(
            "duplicate_allay",
            "Give a dancing Allay an Amethyst Shard to duplicate it.",
            "Use the shard on an Allay that is dancing beside a playing jukebox and is ready to duplicate."),
    COLLAPSE_SCAFFOLDING_TOWER(
            "collapse_scaffolding_tower",
            "Build a 64-block Scaffolding tower and break its base.",
            "All 64 vertical scaffolding blocks must have been placed by you after the card starts."),
    BREED_TRUSTING_FOX(
            "breed_trusting_fox",
            "Breed foxes to gain a cub's trust.",
            "You must breed the two foxes and be recorded as a trusted player on their cub."),
    SHEAR_BOGGED(
            "shear_bogged",
            "Shear a Bogged.",
            "Use shears yourself on a Bogged that has not already been sheared."),
    RING_BELL_PROJECTILE(
            "ring_bell_projectile",
            "Ring a bell with a projectile fired from at least 10 blocks away.",
            "Launch the projectile yourself and make it actually ring the bell after travelling at least ten blocks."),
    BERRY_BUSH_KILL(
            "berry_bush_kill",
            "Plant a sweet berry bush that kills a hostile mob.",
            "Plant the bush after the card starts; the hostile mob must die from damage dealt by that exact bush."),
    DRY_SPONGE_NETHER(
            "dry_sponge_nether",
            "Dry a wet sponge by placing it in the Nether.",
            "Place the wet sponge yourself in the Nether and let it convert into a dry sponge."),
    FIVE_PARROTS_DANCE(
            "five_parrots_dance",
            "Play a jukebox to make all five parrot colours dance together.",
            "Start the jukebox yourself while one dancing parrot of every colour is beside it."),
    DETONATE_TNT_MINECART(
            "detonate_tnt_minecart",
            "Detonate a TNT minecart by lighting fire beneath it with flint and steel or a fire charge.",
            "Create the fire yourself, let the TNT minecart enter that exact fire, and wait for the minecart to explode."),
    TARGET_OPENS_DOOR(
            "target_opens_door",
            "Shoot a target block from at least 10 blocks away to open an adjacent door.",
            "Fire the arrow yourself; it must travel at least ten blocks, hit the target, and power a directly adjacent door open."),
    COLLECT_TURTLE_SCUTE(
            "collect_turtle_scute",
            "Pick up a scute shed by a maturing turtle.",
            "Pick up the Turtle Scute item produced when a baby turtle grows into an adult."),
    SHELF_HOTBAR_SWAP(
            "shelf_hotbar_swap",
            "Use three connected powered shelves to swap your entire hotbar.",
            "Use a chain of three powered, same-facing shelves to exchange all nine shelf slots with all nine hotbar slots at once."),
    REMOVE_PIG_SADDLE(
            "remove_pig_saddle",
            "Remove a pig's saddle with shears without killing it.",
            "Use shears on a living saddled pig and make the saddle drop without the pig dying."),
    EXPLORER_MAP_TRADE(
            "explorer_map_trade",
            "Buy an explorer map from a cartographer villager.",
            "Complete a cartographer trade whose result is a filled explorer map and whose cost includes a compass."),
    SELF_ARROW_TOTEM(
            "self_arrow_totem",
            "Fire an arrow, then survive that same arrow using a Totem of Undying.",
            "Your own arrow must deal the lethal hit that activates a Totem you are holding. This requires Survival and server PvP enabled."),
    EQUIP_PIGLIN_GOLD_ARMOUR(
            "equip_piglin_gold_armour",
            "Give one Piglin a full set of golden armour to equip.",
            "Drop the golden helmet, chestplate, leggings and boots yourself; the same Piglin must pick up and equip all four pieces."),
    LEASHED_BEE_STING(
            "leashed_bee_sting",
            "Leash a bee and have it sting another mob.",
            "Leash the bee yourself, keep it leashed, and make it successfully sting a non-player mob."),
    CREEPER_RINGS_BELL(
            "creeper_rings_bell",
            "Place a pressure plate beside a bell and let a creeper ring it.",
            "Place the pressure plate yourself; a Creeper must step on it and directly power the adjacent bell."),
    MINE_COPPER_GOLEM_STATUE(
            "mine_copper_golem_statue",
            "Mine a copper golem statue so it drops.",
            "Break any copper golem statue variant yourself and make it produce a statue item drop. This normally requires Survival.");

    private static final List<BingoTask> CARD_ONE = List.of(
            GROW_TREE_IN_NETHER,
            PLAY_FIVE_GOAT_HORNS,
            CONNECT_ALL_ORE_TYPES,
            ACTIVATE_TOTEM,
            BREED_MULE,
            KILL_HOSTILE_WITH_ANVIL,
            TWO_CREEPERS_ONE_BOAT,
            IGNITE_CAMPFIRE_FROM_DISTANCE,
            BREED_SNIFFERS_COLLECT_EGG,
            CURE_ZOMBIE_VILLAGER,
            GAIN_AXOLOTL_REGENERATION,
            EQUIP_PIGLIN_BRUTE_AXE,
            KILL_HOSTILE_FROM_CAMEL,
            DUPLICATE_ALLAY,
            COLLAPSE_SCAFFOLDING_TOWER,
            BREED_TRUSTING_FOX);
    private static final List<BingoTask> CARD_TWO = List.of(
            SHEAR_BOGGED,
            RING_BELL_PROJECTILE,
            BERRY_BUSH_KILL,
            DRY_SPONGE_NETHER,
            FIVE_PARROTS_DANCE,
            DETONATE_TNT_MINECART,
            TARGET_OPENS_DOOR,
            COLLECT_TURTLE_SCUTE,
            SHELF_HOTBAR_SWAP,
            REMOVE_PIG_SADDLE,
            EXPLORER_MAP_TRADE,
            SELF_ARROW_TOTEM,
            EQUIP_PIGLIN_GOLD_ARMOUR,
            LEASHED_BEE_STING,
            CREEPER_RINGS_BELL,
            MINE_COPPER_GOLEM_STATUE);
    private static final List<BingoTask> ALL_DEPLOYED = List.of(values());
    private static final Map<String, BingoTask> BY_ID;

    static {
        Map<String, BingoTask> tasksById = new LinkedHashMap<>();
        for (BingoTask task : values()) {
            BingoTask previous = tasksById.put(task.id, task);
            if (previous != null) {
                throw new IllegalStateException("Duplicate bingo task ID: " + task.id);
            }
        }
        BY_ID = Collections.unmodifiableMap(tasksById);
    }

    private final String id;
    private final String description;
    private final String detail;

    BingoTask(String id, String description, String detail) {
        this.id = id;
        this.description = description;
        this.detail = detail;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public String detail() {
        return detail;
    }

    public static List<BingoTask> cardOne() {
        return CARD_ONE;
    }

    public static List<BingoTask> cardTwo() {
        return CARD_TWO;
    }

    public static List<BingoTask> allDeployed() {
        return ALL_DEPLOYED;
    }

    public static Optional<BingoTask> fromId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
