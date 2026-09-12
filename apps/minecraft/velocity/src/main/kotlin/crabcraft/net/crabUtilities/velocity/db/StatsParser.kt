package crabcraft.net.crabUtilities.velocity.db

import com.google.gson.JsonObject

open class StatsParser {
    companion object {
        @JvmStatic fun parse(root: JsonObject?): ComputedStats {
            val stats = ComputedStats()
            if (root == null) return stats
            val allStats = if (root.has("stats")) root.getAsJsonObject("stats") else root
            val custom = getCategory(allStats, "minecraft:custom")
            val mined = getCategory(allStats, "minecraft:mined")
            val crafted = getCategory(allStats, "minecraft:crafted")
            val used = getCategory(allStats, "minecraft:used")
            val killed = getCategory(allStats, "minecraft:killed")
            val killedBy = getCategory(allStats, "minecraft:killed_by")
            val broken = getCategory(allStats, "minecraft:broken")
            // Time (ticks → seconds)
            stats.playTimeSeconds = getInt(custom, "minecraft:play_time") / 20

            // Distances (cm → meters)
            stats.walkDistanceM = getInt(custom, "minecraft:walk_one_cm") / 100.0
            stats.sprintDistanceM = getInt(custom, "minecraft:sprint_one_cm") / 100.0
            stats.swimDistanceM = getInt(custom, "minecraft:swim_one_cm") / 100.0
            stats.flyDistanceM = getInt(custom, "minecraft:fly_one_cm") / 100.0
            stats.boatDistanceM = getInt(custom, "minecraft:boat_one_cm") / 100.0
            stats.elytraDistanceM = getInt(custom, "minecraft:aviate_one_cm") / 100.0
            stats.horseDistanceM = getInt(custom, "minecraft:horse_one_cm") / 100.0
            stats.climbDistanceM = getInt(custom, "minecraft:climb_one_cm") / 100.0
            stats.fallDistanceM = getInt(custom, "minecraft:fall_one_cm") / 100.0
            stats.totalDistanceM = stats.walkDistanceM + stats.sprintDistanceM + stats.swimDistanceM +
                stats.flyDistanceM + stats.boatDistanceM + stats.elytraDistanceM +
                stats.horseDistanceM + stats.climbDistanceM + stats.fallDistanceM

            // Combat
            stats.mobKills = getInt(custom, "minecraft:mob_kills")
            stats.playerKills = getInt(custom, "minecraft:player_kills")
            stats.deaths = getInt(custom, "minecraft:deaths")
            stats.damageDealt = getInt(custom, "minecraft:damage_dealt")
            stats.damageTaken = getInt(custom, "minecraft:damage_taken")

            // Blocks & items
            stats.totalBlocksMined = sumCategory(mined)
            stats.totalBlocksPlaced = sumCategory(used)
            stats.totalItemsCrafted = sumCategory(crafted)
            stats.totalItemsBroken = sumCategory(broken)

            // Misc
            stats.jumps = getInt(custom, "minecraft:jump")
            stats.animalsBred = getInt(custom, "minecraft:animals_bred")
            stats.fishCaught = getInt(custom, "minecraft:fish_caught")
            stats.villagerTraded = getInt(custom, "minecraft:traded_with_villager")
            stats.enchantments = getInt(custom, "minecraft:enchant_item")
            stats.timesSlept = getInt(custom, "minecraft:sleep_in_bed")

            // Top entries
            stats.topBlockMined = topEntry(mined)
            stats.topMobKilled = topEntry(killed)
            stats.topItemCrafted = topEntry(crafted)
            stats.topItemUsed = topEntry(used)
            stats.topDeathCause = topEntry(killedBy)

            return stats
        }

        private fun getCategory(stats: JsonObject, key: String): JsonObject =
        if (stats.has(key)) stats.getAsJsonObject(key) else JsonObject()

        private fun getInt(obj: JsonObject, key: String): Int = obj.get(key)?.asInt ?: 0

        private fun sumCategory(category: JsonObject): Int {
            var sum = 0
            for (entry in category.entrySet()) sum += entry.value.asInt
            return sum
        }

        private fun topEntry(category: JsonObject): String? {
            if (category.isEmpty) return null
            var topId: String? = null
            var topCount = 0
            for (entry in category.entrySet()) {
                val count = entry.value.asInt
                if (count > topCount) {
                    topCount = count
                    topId = entry.key
                }
            }
            if (topId == null) return null
            return "{\"id\":\"" + topId + "\",\"count\":" + topCount + "}"
        }
    }
}
