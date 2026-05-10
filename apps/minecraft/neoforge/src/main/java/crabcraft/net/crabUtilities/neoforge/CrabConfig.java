package crabcraft.net.crabUtilities.neoforge;

import net.minecraftforge.common.ForgeConfigSpec;

public final class CrabConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> SEASON;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST;
    public static final ForgeConfigSpec.IntValue REDIS_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_PASSWORD;
    public static final ForgeConfigSpec.LongValue STATS_INTERVAL_MINUTES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        SEASON = b.comment(
                "Season this server writes data to (e.g. \"6\").",
                "Leave empty to disable stats and advancement publishing.")
                .define("season", "");

        b.comment("Redis - used for publishing stats to the Velocity proxy.").push("redis");
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_PASSWORD = b.define("password", "");
        b.pop();

        b.comment(
                "Periodically scans the world's stats/ and advancements/ folders.",
                "Changed files are published to Redis for the Velocity proxy to",
                "process into awards, leaderboards, and advancement tracking.")
                .push("stats-push");
        STATS_INTERVAL_MINUTES = b.defineInRange("interval-minutes", 5L, 1L, Long.MAX_VALUE);
        b.pop();

        SPEC = b.build();
    }

    private CrabConfig() {}
}
