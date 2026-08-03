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
            "Place 10 ore types plus Ancient Debris in one connected cluster.",
            "Place coal, iron, copper, gold, redstone, lapis, diamond, emerald, Nether quartz, Nether gold and Ancient Debris. Every block must be placed by you after the card starts and touch the cluster by a face; stone and deepslate variants count as the same ore."),
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
    LEASH_RABBIT(
            "leash_rabbit",
            "Leash a rabbit.",
            "Attach a lead to a rabbit yourself; tying an existing lead to a fence does not count."),
    KILL_FULL_IRON_MOB(
            "kill_full_iron_mob",
            "Kill a mob wearing a full set of iron armour.",
            "The mob must be wearing an iron helmet, chestplate, leggings and boots when it dies."),
    LIGHT_COPPER_BULB(
            "light_copper_bulb",
            "Light an unlit copper bulb by placing a redstone torch beside it.",
            "The torch must be placed directly beside a bulb that changes from unlit to lit."),
    BUCKET_AXOLOTL(
            "bucket_axolotl",
            "Catch an axolotl in a bucket.",
            "Use a water bucket on an axolotl."),
    GROW_HUGE_MUSHROOM(
            "grow_huge_mushroom",
            "Grow a huge mushroom using bone meal.",
            "Bone-meal either a red or brown mushroom until it grows into its huge form."),
    HARVEST_CACTUS_FLOWER(
            "harvest_cactus_flower",
            "Harvest a cactus flower.",
            "Break the cactus flower yourself. Break the cactus flower yourself."),
    MILK_GOAT(
            "milk_goat",
            "Milk an adult goat with a bucket.",
            "Right-click an adult goat with an empty bucket so you receive a milk bucket."),
    SUFFOCATE_HOSTILE_WITH_SAND(
            "suffocate_hostile_with_sand",
            "Kill a hostile mob by suffocating it with falling sand.",
            "Place the sand yourself, let it fall onto a hostile mob, and let suffocation deal the final damage."),
    EAT_SUSPICIOUS_STEW(
            "eat_suspicious_stew",
            "Eat a suspicious stew.",
            "The completion event fires when the stew is consumed."),
    BUILD_DRIPLEAF_COLUMN(
            "build_dripleaf_column",
            "Build a 10-block-tall big dripleaf column.",
            "All ten connected blocks must come from your placements or bone-meal actions since the plugin started or you last reset."),
    STUN_RAVAGER(
            "stun_ravager",
            "Stun a ravager by blocking its attack with a shield.",
            "In Survival, genuinely block the ravager's attack with your shield."),
    ARMOUR_WOLF(
            "armour_wolf",
            "Equip a wolf with wolf armour.",
            "The adult tamed wolf must belong to you and gain wolf armour from your interaction."),
    GIVE_MOB_HAT(
            "give_mob_hat",
            "Drop a helmet or carved pumpkin for a mob to wear.",
            "Drop the item yourself; the non-player mob must be able to pick up loot and equip it in its head slot."),
    TAME_PARROT(
            "tame_parrot",
            "Tame a parrot.",
            "You must be the player credited with taming it."),
    CARVE_PUMPKIN(
            "carve_pumpkin",
            "Carve a pumpkin with shears.",
            "Right-click an ordinary pumpkin with shears so it becomes a carved pumpkin."),
    LECTERN_IGNITE_TNT(
            "lectern_ignite_tnt",
            "Turn a lectern page to ignite directly adjacent TNT.",
            "The page turn must produce the redstone pulse that primes TNT touching the lectern by a face.");

    private static final List<BingoTask> ORDERED = List.of(
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
    private static final Map<String, BingoTask> BY_ID;

    static {
        Map<String, BingoTask> tasksById = new LinkedHashMap<>();
        for (BingoTask task : ORDERED) {
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

    public static List<BingoTask> ordered() {
        return ORDERED;
    }

    public static Optional<BingoTask> fromId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
