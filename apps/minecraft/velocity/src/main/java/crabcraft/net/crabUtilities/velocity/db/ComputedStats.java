package crabcraft.net.crabUtilities.velocity.db;

/**
 * POJO holding computed season stats derived from raw Minecraft player statistics.
 */
public class ComputedStats {
    public int playTimeSeconds;
    public double walkDistanceM;
    public double sprintDistanceM;
    public double swimDistanceM;
    public double flyDistanceM;
    public double boatDistanceM;
    public double elytraDistanceM;
    public double horseDistanceM;
    public double climbDistanceM;
    public double fallDistanceM;
    public double totalDistanceM;
    public int mobKills;
    public int playerKills;
    public int deaths;
    public int damageDealt;
    public int damageTaken;
    public int totalBlocksMined;
    public int totalBlocksPlaced;
    public int totalItemsCrafted;
    public int totalItemsBroken;
    public int jumps;
    public int animalsBred;
    public int fishCaught;
    public int villagerTraded;
    public int enchantments;
    public int timesSlept;
    public String topBlockMined;   // JSON: {"id":"...", "count":N}
    public String topMobKilled;
    public String topItemCrafted;
    public String topItemUsed;
    public String topDeathCause;
}
