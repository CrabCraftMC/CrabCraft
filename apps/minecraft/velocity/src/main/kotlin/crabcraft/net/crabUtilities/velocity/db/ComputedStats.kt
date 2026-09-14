package crabcraft.net.crabUtilities.velocity.db

/** POJO holding computed season stats derived from raw Minecraft player statistics. */
open class ComputedStats {
    @JvmField var playTimeSeconds: Int = 0
    @JvmField var walkDistanceM: Double = 0.0
    @JvmField var sprintDistanceM: Double = 0.0
    @JvmField var swimDistanceM: Double = 0.0
    @JvmField var flyDistanceM: Double = 0.0
    @JvmField var boatDistanceM: Double = 0.0
    @JvmField var elytraDistanceM: Double = 0.0
    @JvmField var horseDistanceM: Double = 0.0
    @JvmField var climbDistanceM: Double = 0.0
    @JvmField var fallDistanceM: Double = 0.0
    @JvmField var totalDistanceM: Double = 0.0
    @JvmField var mobKills: Int = 0
    @JvmField var playerKills: Int = 0
    @JvmField var deaths: Int = 0
    @JvmField var damageDealt: Int = 0
    @JvmField var damageTaken: Int = 0
    @JvmField var totalBlocksMined: Int = 0
    @JvmField var totalBlocksPlaced: Int = 0
    @JvmField var totalItemsCrafted: Int = 0
    @JvmField var totalItemsBroken: Int = 0
    @JvmField var jumps: Int = 0
    @JvmField var animalsBred: Int = 0
    @JvmField var fishCaught: Int = 0
    @JvmField var villagerTraded: Int = 0
    @JvmField var enchantments: Int = 0
    @JvmField var timesSlept: Int = 0
    @JvmField var topBlockMined: String? = null   // JSON: {"id":"...", "count":N}
    @JvmField var topMobKilled: String? = null
    @JvmField var topItemCrafted: String? = null
    @JvmField var topItemUsed: String? = null
    @JvmField var topDeathCause: String? = null
}
