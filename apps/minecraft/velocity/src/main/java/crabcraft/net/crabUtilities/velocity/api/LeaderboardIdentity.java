package crabcraft.net.crabUtilities.velocity.api;

import com.google.gson.JsonObject;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Redacts identities before optional hidden entries reach a public response. */
public final class LeaderboardIdentity {
    private LeaderboardIdentity() {}

    public static void addTo(JsonObject entry, ResultSet row) throws SQLException {
        boolean hidden = row.getBoolean("hidden");
        entry.addProperty("hidden", hidden);
        entry.addProperty("uuid", hidden ? null : row.getString("minecraft_uuid"));
        entry.addProperty("username", hidden ? "Hidden player" : row.getString("minecraft_username"));
        entry.addProperty("nickname", hidden ? null : row.getString("nickname"));
    }
}
