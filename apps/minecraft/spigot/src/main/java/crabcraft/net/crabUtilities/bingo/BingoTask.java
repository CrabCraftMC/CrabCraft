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
            "Break any copper golem statue variant yourself and make it produce a statue item drop. This normally requires Survival."),
    SULFUR_CUBE_TNT_IGNITE(
            "sulfur_cube_tnt_ignite",
            "Feed TNT to a Sulfur Cube, then ignite it.",
            "Give TNT to a Sulfur Cube yourself, then personally ignite that same Sulfur Cube."),
    BREED_THIRD_COLOUR_SHEEP(
            "breed_third_colour_sheep",
            "Breed two sheep to produce a third colour.",
            "Breed two differently coloured sheep so their lamb has a colour different from both parents."),
    UNLOCK_OMINOUS_VAULT(
            "unlock_ominous_vault",
            "Unlock an Ominous Vault.",
            "Use an Ominous Trial Key yourself to unlock an Ominous Vault that has not rewarded you before."),
    FOX_USES_TOTEM(
            "fox_uses_totem",
            "Give a fox a Totem of Undying and make it activate it.",
            "Drop a Totem of Undying that a fox picks up, then make that same fox survive fatal damage by activating it."),
    TAME_NAUTILUS(
            "tame_nautilus",
            "Tame a Nautilus.",
            "Successfully tame a Nautilus yourself after this card starts."),
    JOHNNY_VINDICATOR_KILL(
            "johnny_vindicator_kill",
            "Name a Vindicator Johnny and have it kill a hostile mob.",
            "Use a name tag to name a Vindicator Johnny, then have that same Vindicator deal the final blow to another hostile mob."),
    HOOK_GHAST(
            "hook_ghast",
            "Hook a Ghast with a fishing rod.",
            "Cast the fishing rod yourself and make its hook catch a Ghast."),
    WATER_BOTTLE_EXTINGUISH_THREE(
            "water_bottle_extinguish_three",
            "Extinguish three burning mobs with one splash water bottle.",
            "Throw one splash water bottle that extinguishes at least three distinct burning non-player mobs."),
    SHULKER_BULLET_DUPLICATE(
            "shulker_bullet_duplicate",
            "Use a Shulker bullet aimed at you to duplicate another Shulker.",
            "Make a Shulker bullet that was targeting you hit a different Shulker and cause it to duplicate."),
    WARM_RIDDEN_STRIDER(
            "warm_ridden_strider",
            "Ride a shivering Strider into lava to warm it up.",
            "Mount a cold, shivering Strider and remain its rider as it enters lava and becomes warm."),
    RAID_BELL_REVEAL_THREE(
            "raid_bell_reveal_three",
            "Ring a bell during a raid to reveal three raiders.",
            "Ring a bell yourself during an active raid and make at least three surviving raiders receive the glowing reveal effect."),
    PIERCING_ARROW_HIT_THREE(
            "piercing_arrow_hit_three",
            "Hit three mobs with one Piercing crossbow arrow.",
            "Fire a Piercing-enchanted crossbow arrow that damages at least three distinct non-player mobs before it stops."),
    LEASHED_FROG_FROGLIGHT(
            "leashed_frog_froglight",
            "Keep a Frog leashed while it creates a Froglight.",
            "Leash a Frog yourself, either by holding its lead or tying it to a fence, and keep it leashed until that same Frog eats a small Magma Cube and creates a Froglight."),
    CHARGED_CREEPER_MOB_HEAD(
            "charged_creeper_mob_head",
            "Charge a Creeper, then use it to obtain a mob head.",
            "Charge a Creeper with lightning from your Channeling trident, then have that same Creeper explode and produce a mob head."),
    GOLDEN_DANDELION_HOGLIN(
            "golden_dandelion_hoglin",
            "Stop a baby Hoglin ageing with a Golden Dandelion.",
            "Use a Golden Dandelion yourself on a baby Hoglin so it permanently stops ageing."),
    FOUR_COPPER_TRUMPET_SOUNDS(
            "four_copper_trumpet_sounds",
            "Play all four copper-trumpet sounds with Note Blocks.",
            "Personally play the copper-trumpet Note Block sound once at each of the four copper oxidation stages."),
    SULFUR_CUBE_DIAMOND_BUCKET(
            "sulfur_cube_diamond_bucket",
            "Catch a Sulfur Cube carrying a Diamond Block in a bucket.",
            "Use an empty Bucket to catch an adult, unignited Sulfur Cube while it is carrying a Diamond Block."),
    SNOW_GOLEM_KILLS_BLAZE(
            "snow_golem_kills_blaze",
            "Build a Snow Golem and have it kill a Blaze.",
            "Build a Snow Golem yourself, then have that same golem's snowball deal the final blow to a Blaze."),
    FOUR_BY_FOUR_NETHER_PORTAL(
            "four_by_four_nether_portal",
            "Build and light a Nether portal with a 4×4 interior.",
            "Build a Nether portal frame whose open interior is exactly four blocks wide and four blocks high, then light it yourself."),
    CROSSBOW_FIREWORK_KILL_TWO(
            "crossbow_firework_kill_two",
            "Kill two hostile mobs with one crossbow Firework.",
            "Fire a Firework Rocket from a crossbow and make that single rocket deal the final blow to two distinct hostile mobs."),
    HAPPY_GHAST_HOSTILE_BOAT(
            "happy_ghast_hostile_boat",
            "Leash a Boat carrying a hostile mob to a harnessed Happy Ghast.",
            "Equip an adult Happy Ghast with a harness yourself, then personally leash a Boat carrying a hostile mob to that same ghast."),
    SPEAR_HIT_THREE(
            "spear_hit_three",
            "Hit three mobs with one Spear charge.",
            "Use one charged Spear attack to damage three distinct non-player mobs."),
    SILVERFISH_HIDE_IN_STONE(
            "silverfish_hide_in_stone",
            "Name a Silverfish, then let it hide inside stone.",
            "Name a Silverfish yourself, then let that exact Silverfish enter a compatible stone block and turn it into an infested block."),
    TREE_WITH_BEE_NEST(
            "tree_with_bee_nest",
            "Use bone meal to grow a tree containing a Bee Nest.",
            "Use bone meal on a sapling and make that growth generate a tree containing a Bee Nest."),
    BROWN_MOOSHROOM_WITHER_STEW(
            "brown_mooshroom_wither_stew",
            "Feed a Brown Mooshroom a Wither Rose, then milk its Suspicious Stew.",
            "Feed a Wither Rose to a Brown Mooshroom yourself, then use a Bowl on that same Mooshroom to collect its Suspicious Stew."),
    PISTON_PUSH_TWELVE(
            "piston_push_twelve",
            "Use a Lever attached to a Piston to push 12 blocks.",
            "Use a Lever attached directly to a Piston and make that Piston extend while pushing the maximum load of 12 blocks."),
    ENDER_PEARL_TELEPORT_HUNDRED(
            "ender_pearl_teleport_hundred",
            "Teleport 100+ horizontal blocks with one Ender Pearl.",
            "Use one Ender Pearl to teleport at least 100 blocks horizontally from where you started. Vertical distance does not count."),
    REFLECTED_BREEZE_WIND_CHARGE(
            "reflected_breeze_wind_charge",
            "Kill a Breeze with its own deflected Wind Charge.",
            "Deflect a Breeze's Wind Charge yourself and make that reflected projectile deal the final blow to the Breeze that fired it."),
    PROJECTILE_SMASH_FILLED_POT(
            "projectile_smash_filled_pot",
            "Smash a filled Decorated Pot with a projectile fired from 10+ blocks.",
            "Fire a projectile that travels at least ten blocks before smashing a Decorated Pot that contains an item."),
    CURE_POISON_HONEY_BOTTLE(
            "cure_poison_honey_bottle",
            "Cure Poison by drinking a Honey Bottle.",
            "Drink a Honey Bottle while poisoned and remove the Poison effect."),
    FREEZE_SKELETON_STRAY(
            "freeze_skeleton_stray",
            "Freeze a Skeleton into a Stray using Powder Snow you placed.",
            "Place the Powder Snow yourself, then keep a Skeleton inside that Powder Snow until it converts into a Stray."),
    SCULK_CATALYST_PLAYER_KILL(
            "sculk_catalyst_player_kill",
            "Kill a hostile mob beside a Sculk Catalyst to spread Sculk.",
            "Personally kill a hostile mob close enough to a Sculk Catalyst for its experience to make that catalyst spread Sculk."),
    BUILD_TEN_TALL_DRIPLEAF(
            "build_ten_tall_dripleaf",
            "Build a 10-block-tall Big Dripleaf.",
            "Place or grow every block in one vertical column of at least ten Big Dripleaf stems and leaves after the card starts."),
    MOB_EQUIPS_DROPPED_HELMET(
            "mob_equips_dropped_helmet",
            "Drop a helmet that a hostile mob equips.",
            "Drop a wearable helmet, mob head or carved pumpkin yourself and have a hostile mob pick up and equip that exact item."),
    CLEAN_BANNER_PATTERN(
            "clean_banner_pattern",
            "Clean a pattern off a Banner.",
            "Use a water-filled Cauldron to remove at least one pattern from a Banner you are holding."),
    FEED_PANDA_CAKE(
            "feed_panda_cake",
            "Feed a Panda a Cake.",
            "Drop a Cake yourself and have a Panda pick up and eat that exact dropped item."),
    REMOVE_ENCHANTMENT_GRINDSTONE(
            "remove_enchantment_grindstone",
            "Remove an enchantment with a Grindstone.",
            "Take a Grindstone result that removes at least one non-curse enchantment from an input item."),
    NAME_HOGLIN_ZOGLIN(
            "name_hoglin_zoglin",
            "Name a Hoglin, then convert it into a Zoglin.",
            "Name a Hoglin yourself, move that exact Hoglin out of the Nether and wait for it to convert into a Zoglin."),
    LODESTONE_COMPASS(
            "lodestone_compass",
            "Make a Compass point to a Lodestone.",
            "Use a Compass on a Lodestone yourself so that the Compass becomes bound to that block."),
    ENDERMAN_KILLED_BY_ENDERMITES_ONLY(
            "enderman_killed_by_endermites_only",
            "Name an Enderman, then have only Endermites kill it.",
            "Name an Enderman yourself; from then until it dies, every source of damage must be an Endermite and an Endermite must deal the final blow."),
    FILL_CHISELED_BOOKSHELF_ENCHANTED(
            "fill_chiseled_bookshelf_enchanted",
            "Fill a Chiseled Bookshelf with Enchanted Books.",
            "Insert the final Enchanted Book yourself so that all six slots of one Chiseled Bookshelf contain Enchanted Books."),
    DISARM_PILLAGER(
            "disarm_pillager",
            "Disarm a Pillager by making it break its Crossbow.",
            "Keep a Pillager targeting you until a shot uses up its Crossbow and leaves that Pillager unarmed."),
    HATCH_THROWN_CHICKEN(
            "hatch_thrown_chicken",
            "Hatch a Chicken from an Egg you throw.",
            "Throw an Egg yourself and have that exact Egg hatch at least one Chicken."),
    SNOW_EVERY_HEIGHT(
            "snow_every_height",
            "Place every height of Snow next to each other.",
            "After the card starts, place a connected set of eight Snow layers whose thicknesses cover every value from one through eight."),
    REPAIR_IRON_GOLEM(
            "repair_iron_golem",
            "Repair a damaged Iron Golem.",
            "Use an Iron Ingot yourself to restore health to an Iron Golem that was already damaged."),
    POWER_FURNACE_MINECART(
            "power_furnace_minecart",
            "Power a Minecart with Furnace.",
            "Use Coal or Charcoal yourself to add fuel to a Minecart with Furnace."),
    PLAYER_END_CRYSTAL_HOSTILE_KILL(
            "player_end_crystal_hostile_kill",
            "Kill a hostile mob with an End Crystal you placed.",
            "Place an End Crystal yourself and make that exact Crystal's explosion deal the final blow to a hostile mob."),
    WEAR_FOUR_ARMOUR_MATERIALS(
            "wear_four_armour_materials",
            "Wear four different armour materials at once.",
            "Simultaneously equip a helmet, chestplate, leggings and boots whose four base material families are all different."),
    HANG_FOUR_BY_FOUR_PAINTING(
            "hang_four_by_four_painting",
            "Hang up a 4×4 Painting.",
            "Place a Painting yourself and make it resolve to artwork that is four blocks wide and four blocks high."),
    OUTLINE_HANGING_SIGN(
            "outline_hanging_sign",
            "Outline text on a Hanging Sign.",
            "Use a Glow Ink Sac yourself on a Hanging Sign that contains text so its letters become outlined."),
    FILL_CAMPFIRE_FOUR_SLOTS(
            "fill_campfire_four_slots",
            "Fill all four slots of a Campfire.",
            "Insert the fourth cooking item yourself so all four slots of one Campfire are occupied."),
    SHOOT_BUTTON_WITH_ARROW(
            "shoot_button_with_arrow",
            "Shoot a Button with an Arrow.",
            "Fire an Arrow yourself and make that exact Arrow hit and power a wooden Button."),
    FISH_TREASURE_AND_JUNK(
            "fish_treasure_and_junk",
            "Fish both a Treasure and Junk item.",
            "Personally catch at least one item from Minecraft's fishing treasure category and one from its junk category after the card starts."),
    CARPET_LLAMA(
            "carpet_llama",
            "Put a Carpet on a Llama.",
            "Equip a Carpet onto a living Llama yourself, either directly or through its inventory."),
    ENCHANT_FIVE_ITEMS(
            "enchant_five_items",
            "Enchant five different items using an Enchanting Table.",
            "Successfully enchant five distinct base item types at an Enchanting Table after the card starts."),
    THROW_MENDING_BOOK_IN_LAVA(
            "throw_mending_book_in_lava",
            "Throw a Mending Book into Lava.",
            "Drop an Enchanted Book containing Mending yourself and let that exact dropped item be destroyed by Lava."),
    STUN_RAVAGER(
            "stun_ravager",
            "Stun a Ravager with a Shield.",
            "Block a Ravager's attack with a Shield and make that Ravager enter its stunned state."),
    FULLY_POWER_CONDUIT(
            "fully_power_conduit",
            "Fully power a Conduit.",
            "Place the final prismarine-family frame block yourself so an active Conduit reaches its maximum 42-frame-block power."),
    POISON_BEE(
            "poison_bee",
            "Poison a Bee with a potion you threw.",
            "Throw a Splash or Lingering Potion yourself and successfully apply Poison to a Bee."),
    PLACE_FISH_IN_NETHER(
            "place_fish_in_nether",
            "Place a Fish in the Nether.",
            "Empty a Cod, Salmon, Pufferfish or Tropical Fish Bucket yourself while in the Nether."),
    NAMED_GHAST_OVERWORLD(
            "named_ghast_overworld",
            "Name a Ghast, then send it into the Overworld.",
            "Name a regular Ghast yourself, then make that exact Ghast travel through a Nether Portal into the Overworld."),
    FILL_ENDER_CHEST(
            "fill_ender_chest",
            "Fill every slot of your Ender Chest.",
            "Insert the final item stack yourself so all 27 slots of your Ender Chest are occupied."),
    APPLY_ARMOUR_TRIM(
            "apply_armour_trim",
            "Apply an Armour Trim.",
            "Take a Smithing Table result that applies a new Armour Trim to a piece of armour."),
    FOUR_SHERD_DECORATED_POT(
            "four_sherd_decorated_pot",
            "Craft a Decorated Pot with four different Pottery Sherds.",
            "Personally craft a Decorated Pot whose four input faces use four distinct Pottery Sherd types.");

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
    private static final List<BingoTask> CARD_THREE = List.of(
            SULFUR_CUBE_TNT_IGNITE,
            BREED_THIRD_COLOUR_SHEEP,
            UNLOCK_OMINOUS_VAULT,
            FOX_USES_TOTEM,
            TAME_NAUTILUS,
            JOHNNY_VINDICATOR_KILL,
            HOOK_GHAST,
            WATER_BOTTLE_EXTINGUISH_THREE,
            SHULKER_BULLET_DUPLICATE,
            WARM_RIDDEN_STRIDER,
            RAID_BELL_REVEAL_THREE,
            PIERCING_ARROW_HIT_THREE,
            LEASHED_FROG_FROGLIGHT,
            CHARGED_CREEPER_MOB_HEAD,
            GOLDEN_DANDELION_HOGLIN,
            FOUR_COPPER_TRUMPET_SOUNDS);
    private static final List<BingoTask> CARD_FOUR = List.of(
            SULFUR_CUBE_DIAMOND_BUCKET,
            SNOW_GOLEM_KILLS_BLAZE,
            FOUR_BY_FOUR_NETHER_PORTAL,
            CROSSBOW_FIREWORK_KILL_TWO,
            HAPPY_GHAST_HOSTILE_BOAT,
            SPEAR_HIT_THREE,
            SILVERFISH_HIDE_IN_STONE,
            TREE_WITH_BEE_NEST,
            BROWN_MOOSHROOM_WITHER_STEW,
            PISTON_PUSH_TWELVE,
            ENDER_PEARL_TELEPORT_HUNDRED,
            REFLECTED_BREEZE_WIND_CHARGE,
            PROJECTILE_SMASH_FILLED_POT,
            CURE_POISON_HONEY_BOTTLE,
            FREEZE_SKELETON_STRAY,
            SCULK_CATALYST_PLAYER_KILL);
    private static final List<BingoTask> CARD_FIVE = List.of(
            BUILD_TEN_TALL_DRIPLEAF,
            MOB_EQUIPS_DROPPED_HELMET,
            CLEAN_BANNER_PATTERN,
            FEED_PANDA_CAKE,
            REMOVE_ENCHANTMENT_GRINDSTONE,
            NAME_HOGLIN_ZOGLIN,
            LODESTONE_COMPASS,
            ENDERMAN_KILLED_BY_ENDERMITES_ONLY,
            FILL_CHISELED_BOOKSHELF_ENCHANTED,
            DISARM_PILLAGER,
            HATCH_THROWN_CHICKEN,
            SNOW_EVERY_HEIGHT,
            REPAIR_IRON_GOLEM,
            POWER_FURNACE_MINECART,
            PLAYER_END_CRYSTAL_HOSTILE_KILL,
            WEAR_FOUR_ARMOUR_MATERIALS);
    private static final List<BingoTask> CARD_SIX = List.of(
            HANG_FOUR_BY_FOUR_PAINTING,
            OUTLINE_HANGING_SIGN,
            FILL_CAMPFIRE_FOUR_SLOTS,
            SHOOT_BUTTON_WITH_ARROW,
            FISH_TREASURE_AND_JUNK,
            CARPET_LLAMA,
            ENCHANT_FIVE_ITEMS,
            THROW_MENDING_BOOK_IN_LAVA,
            STUN_RAVAGER,
            FULLY_POWER_CONDUIT,
            POISON_BEE,
            PLACE_FISH_IN_NETHER,
            NAMED_GHAST_OVERWORLD,
            FILL_ENDER_CHEST,
            APPLY_ARMOUR_TRIM,
            FOUR_SHERD_DECORATED_POT);
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

    public static List<BingoTask> cardThree() {
        return CARD_THREE;
    }

    public static List<BingoTask> cardFour() {
        return CARD_FOUR;
    }

    public static List<BingoTask> cardFive() {
        return CARD_FIVE;
    }

    public static List<BingoTask> cardSix() {
        return CARD_SIX;
    }

    public static List<BingoTask> allDeployed() {
        return ALL_DEPLOYED;
    }

    public static Optional<BingoTask> fromId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
