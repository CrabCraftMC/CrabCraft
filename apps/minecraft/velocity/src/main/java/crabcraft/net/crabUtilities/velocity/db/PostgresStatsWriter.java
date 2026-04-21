package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PostgresStatsWriter {

    private final HikariDataSource dataSource;
    private final Logger logger;

    private static final String UPSERT_SQL = """
        INSERT INTO player_season_stats (
            minecraft_uuid, season,
            play_time_seconds, walk_distance_m, sprint_distance_m,
            swim_distance_m, fly_distance_m, boat_distance_m,
            elytra_distance_m, horse_distance_m, climb_distance_m,
            fall_distance_m, total_distance_m,
            mob_kills, player_kills, deaths,
            damage_dealt, damage_taken,
            total_blocks_mined, total_blocks_placed,
            total_items_crafted, total_items_broken,
            jumps, animals_bred, fish_caught,
            villagers_traded, enchantments, times_slept,
            top_block_mined, top_mob_killed, top_item_crafted,
            top_item_used, top_death_cause,
            computed_at
        ) VALUES (
            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
            EXTRACT(EPOCH FROM NOW())::INTEGER
        )
        ON CONFLICT (minecraft_uuid, season) DO UPDATE SET
            play_time_seconds = EXCLUDED.play_time_seconds,
            walk_distance_m = EXCLUDED.walk_distance_m,
            sprint_distance_m = EXCLUDED.sprint_distance_m,
            swim_distance_m = EXCLUDED.swim_distance_m,
            fly_distance_m = EXCLUDED.fly_distance_m,
            boat_distance_m = EXCLUDED.boat_distance_m,
            elytra_distance_m = EXCLUDED.elytra_distance_m,
            horse_distance_m = EXCLUDED.horse_distance_m,
            climb_distance_m = EXCLUDED.climb_distance_m,
            fall_distance_m = EXCLUDED.fall_distance_m,
            total_distance_m = EXCLUDED.total_distance_m,
            mob_kills = EXCLUDED.mob_kills,
            player_kills = EXCLUDED.player_kills,
            deaths = EXCLUDED.deaths,
            damage_dealt = EXCLUDED.damage_dealt,
            damage_taken = EXCLUDED.damage_taken,
            total_blocks_mined = EXCLUDED.total_blocks_mined,
            total_blocks_placed = EXCLUDED.total_blocks_placed,
            total_items_crafted = EXCLUDED.total_items_crafted,
            total_items_broken = EXCLUDED.total_items_broken,
            jumps = EXCLUDED.jumps,
            animals_bred = EXCLUDED.animals_bred,
            fish_caught = EXCLUDED.fish_caught,
            villagers_traded = EXCLUDED.villagers_traded,
            enchantments = EXCLUDED.enchantments,
            times_slept = EXCLUDED.times_slept,
            top_block_mined = EXCLUDED.top_block_mined,
            top_mob_killed = EXCLUDED.top_mob_killed,
            top_item_crafted = EXCLUDED.top_item_crafted,
            top_item_used = EXCLUDED.top_item_used,
            top_death_cause = EXCLUDED.top_death_cause,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        """;

    public PostgresStatsWriter(String jdbcUrl, String username, String password, Logger logger) {
        this.logger = logger;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setConnectionTimeout(5000);
        config.setPoolName("CrabUtilities-PG");
        // Velocity isolates plugin classloaders, so DriverManager's
        // ServiceLoader-based discovery can't see the driver bundled
        // inside this plugin JAR. Naming the driver class explicitly
        // makes Hikari load it via Class.forName on the plugin's own
        // classloader, which always succeeds.
        config.setDriverClassName("org.postgresql.Driver");
        this.dataSource = new HikariDataSource(config);
    }

    public void writePlayerSeasonStats(String uuid, String season, ComputedStats stats) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            int i = 1;
            stmt.setString(i++, uuid);
            stmt.setString(i++, season);
            stmt.setInt(i++, stats.playTimeSeconds);
            stmt.setDouble(i++, stats.walkDistanceM);
            stmt.setDouble(i++, stats.sprintDistanceM);
            stmt.setDouble(i++, stats.swimDistanceM);
            stmt.setDouble(i++, stats.flyDistanceM);
            stmt.setDouble(i++, stats.boatDistanceM);
            stmt.setDouble(i++, stats.elytraDistanceM);
            stmt.setDouble(i++, stats.horseDistanceM);
            stmt.setDouble(i++, stats.climbDistanceM);
            stmt.setDouble(i++, stats.fallDistanceM);
            stmt.setDouble(i++, stats.totalDistanceM);
            stmt.setInt(i++, stats.mobKills);
            stmt.setInt(i++, stats.playerKills);
            stmt.setInt(i++, stats.deaths);
            stmt.setInt(i++, stats.damageDealt);
            stmt.setInt(i++, stats.damageTaken);
            stmt.setInt(i++, stats.totalBlocksMined);
            stmt.setInt(i++, stats.totalBlocksPlaced);
            stmt.setInt(i++, stats.totalItemsCrafted);
            stmt.setInt(i++, stats.totalItemsBroken);
            stmt.setInt(i++, stats.jumps);
            stmt.setInt(i++, stats.animalsBred);
            stmt.setInt(i++, stats.fishCaught);
            stmt.setInt(i++, stats.villagerTraded);
            stmt.setInt(i++, stats.enchantments);
            stmt.setInt(i++, stats.timesSlept);
            stmt.setString(i++, stats.topBlockMined);
            stmt.setString(i++, stats.topMobKilled);
            stmt.setString(i++, stats.topItemCrafted);
            stmt.setString(i++, stats.topItemUsed);
            stmt.setString(i++, stats.topDeathCause);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to write player season stats for {}", uuid, e);
        }
    }

    /**
     * Updates player info (username + nickname) by minecraft_uuid.
     * Only updates existing players — no-op if UUID not in DB.
     */
    public void upsertPlayer(String uuid, String username, String nickname, String nicknameRaw) {
        String sql = "UPDATE players SET minecraft_username = ?, nickname = ?, nickname_raw = ?, updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER WHERE minecraft_uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, nickname);
            stmt.setString(3, nicknameRaw);
            stmt.setString(4, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to upsert player {}", uuid, e);
        }
    }

    /**
     * Updates just the nickname for a player by minecraft_uuid.
     */
    public void updateNickname(String uuid, String nickname, String nicknameRaw) {
        String sql = "UPDATE players SET nickname = ?, nickname_raw = ?, updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER WHERE minecraft_uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nickname);
            stmt.setString(2, nicknameRaw);
            stmt.setString(3, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update nickname for {}", uuid, e);
        }
    }

    /**
     * Shared HikariCP pool. Exposed so auxiliary writers (awards, crown
     * scores) can reuse the same connection pool rather than opening their
     * own.
     */
    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
