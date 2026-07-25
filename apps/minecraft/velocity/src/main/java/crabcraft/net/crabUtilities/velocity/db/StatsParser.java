package crabcraft.net.crabUtilities.velocity.db;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

public class StatsParser {

    public static ComputedStats parse(JsonObject root) {
        ComputedStats stats = new ComputedStats();
        if (root == null) return stats;

        JsonObject allStats = root.has("stats") ? root.getAsJsonObject("stats") : root;

        JsonObject custom = getCategory(allStats, "minecraft:custom");
        JsonObject mined = getCategory(allStats, "minecraft:mined");
        JsonObject crafted = getCategory(allStats, "minecraft:crafted");
        JsonObject used = getCategory(allStats, "minecraft:used");
        JsonObject killed = getCategory(allStats, "minecraft:killed");
        JsonObject killedBy = getCategory(allStats, "minecraft:killed_by");
        JsonObject broken = getCategory(allStats, "minecraft:broken");

        // Time (ticks → seconds)
        stats.playTimeSeconds = getInt(custom, "minecraft:play_time") / 20;

        // Distances (cm → meters)
        stats.walkDistanceM = getInt(custom, "minecraft:walk_one_cm") / 100.0;
        stats.sprintDistanceM = getInt(custom, "minecraft:sprint_one_cm") / 100.0;
        stats.swimDistanceM = getInt(custom, "minecraft:swim_one_cm") / 100.0;
        stats.flyDistanceM = getInt(custom, "minecraft:fly_one_cm") / 100.0;
        stats.boatDistanceM = getInt(custom, "minecraft:boat_one_cm") / 100.0;
        stats.elytraDistanceM = getInt(custom, "minecraft:aviate_one_cm") / 100.0;
        stats.horseDistanceM = getInt(custom, "minecraft:horse_one_cm") / 100.0;
        stats.climbDistanceM = getInt(custom, "minecraft:climb_one_cm") / 100.0;
        stats.fallDistanceM = getInt(custom, "minecraft:fall_one_cm") / 100.0;
        stats.totalDistanceM = stats.walkDistanceM + stats.sprintDistanceM + stats.swimDistanceM
                + stats.flyDistanceM + stats.boatDistanceM + stats.elytraDistanceM
                + stats.horseDistanceM + stats.climbDistanceM + stats.fallDistanceM;

        // Combat
        stats.mobKills = getInt(custom, "minecraft:mob_kills");
        stats.playerKills = getInt(custom, "minecraft:player_kills");
        stats.deaths = getInt(custom, "minecraft:deaths");
        stats.damageDealt = getInt(custom, "minecraft:damage_dealt");
        stats.damageTaken = getInt(custom, "minecraft:damage_taken");

        // Blocks & items
        stats.totalBlocksMined = sumCategory(mined);
        stats.totalBlocksPlaced = sumCategory(used);
        stats.totalItemsCrafted = sumCategory(crafted);
        stats.totalItemsBroken = sumCategory(broken);

        // Misc
        stats.jumps = getInt(custom, "minecraft:jump");
        stats.animalsBred = getInt(custom, "minecraft:animals_bred");
        stats.fishCaught = getInt(custom, "minecraft:fish_caught");
        stats.villagerTraded = getInt(custom, "minecraft:traded_with_villager");
        stats.enchantments = getInt(custom, "minecraft:enchant_item");
        stats.timesSlept = getInt(custom, "minecraft:sleep_in_bed");

        // Top entries
        stats.topBlockMined = topEntry(mined);
        stats.topMobKilled = topEntry(killed);
        stats.topItemCrafted = topEntry(crafted);
        stats.topItemUsed = topEntry(used);
        stats.topDeathCause = topEntry(killedBy);

        return stats;
    }

    private static JsonObject getCategory(JsonObject stats, String key) {
        return stats.has(key) ? stats.getAsJsonObject(key) : new JsonObject();
    }

    private static int getInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null ? el.getAsInt() : 0;
    }

    private static int sumCategory(JsonObject category) {
        int sum = 0;
        for (Map.Entry<String, JsonElement> entry : category.entrySet()) {
            sum += entry.getValue().getAsInt();
        }
        return sum;
    }

    private static String topEntry(JsonObject category) {
        if (category.isEmpty()) return null;
        String topId = null;
        int topCount = 0;
        for (Map.Entry<String, JsonElement> entry : category.entrySet()) {
            int count = entry.getValue().getAsInt();
            if (count > topCount) {
                topCount = count;
                topId = entry.getKey();
            }
        }
        if (topId == null) return null;
        return "{\"id\":\"" + topId + "\",\"count\":" + topCount + "}";
    }
}
