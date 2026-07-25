package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class AltQueryService {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AltQueryService(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    /**
     * Check if a Minecraft UUID is registered as an alt account.
     * Called once per proxy join — zero overhead when nobody is joining.
     */
    public boolean isAlt(String minecraftUuid) {
        String sql = "SELECT 1 FROM player_alts WHERE minecraft_uuid = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, minecraftUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Failed to check alt status for {}", minecraftUuid, e);
            return false;
        }
    }
}
