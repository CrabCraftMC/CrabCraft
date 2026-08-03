package crabcraft.net.crabUtilities.bingo;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stable task identifiers and descriptions for the first production bingo card. */
public enum BingoTask {
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

    private static final List<BingoTask> ORDERED = List.copyOf(Arrays.asList(values()));
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

    public static List<BingoTask> ordered() {
        return ORDERED;
    }

    public static Optional<BingoTask> fromId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
