package crabcraft.net.crabUtilities.velocity.db;

import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariDataSource;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol.Status;
import crabcraft.net.crabUtilities.tags.TagDefinition;
import crabcraft.net.crabUtilities.tags.TagSnapshot;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/** Authoritative collectible ownership and selection, shared with the bot's PostgreSQL writer. */
public final class TagRepository {
    private static final String[] SCHEMA_SQL = {
            """
            CREATE TABLE IF NOT EXISTS cosmetic_tags (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                glyph_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                enabled BOOLEAN NOT NULL DEFAULT TRUE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS player_tags (
                minecraft_uuid TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                granted_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                granted_by TEXT NOT NULL,
                PRIMARY KEY (minecraft_uuid, tag_id),
                CONSTRAINT player_tags_tag_id_cosmetic_tags_id_fk FOREIGN KEY (tag_id) REFERENCES cosmetic_tags(id)
            )
            """,
            "CREATE INDEX IF NOT EXISTS player_tags_tag_id_idx ON player_tags (tag_id)",
            """
            CREATE TABLE IF NOT EXISTS player_tag_state (
                minecraft_uuid TEXT PRIMARY KEY,
                selected_tag_id TEXT,
                revision BIGINT NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                CONSTRAINT player_tag_state_owned_tag_fk FOREIGN KEY (minecraft_uuid, selected_tag_id)
                    REFERENCES player_tags(minecraft_uuid, tag_id),
                CONSTRAINT player_tag_state_revision_check CHECK (revision >= 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS player_tag_audit (
                id TEXT PRIMARY KEY,
                minecraft_uuid TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                action TEXT NOT NULL,
                ownership_changed BOOLEAN NOT NULL,
                actor TEXT NOT NULL,
                source TEXT NOT NULL,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                source_key TEXT,
                CONSTRAINT player_tag_audit_tag_id_cosmetic_tags_id_fk FOREIGN KEY (tag_id) REFERENCES cosmetic_tags(id),
                CONSTRAINT player_tag_audit_action_check CHECK (action IN ('grant', 'revoke')),
                CONSTRAINT player_tag_audit_source_key_unique UNIQUE (source_key)
            )
            """,
            "CREATE INDEX IF NOT EXISTS player_tag_audit_player_created_idx ON player_tag_audit (minecraft_uuid, created_at)"
    };

    // One statement provides a coherent MVCC snapshot, including when no state row exists yet.
    private static final String SNAPSHOT_SQL = """
            SELECT COALESCE(s.revision, 0) AS revision, s.selected_tag_id,
                   t.id, t.name, t.description, t.glyph_id, t.sort_order, t.enabled
            FROM (SELECT CAST(? AS TEXT) AS minecraft_uuid) p
            LEFT JOIN player_tag_state s ON s.minecraft_uuid = p.minecraft_uuid
            LEFT JOIN player_tags o ON o.minecraft_uuid = p.minecraft_uuid
            LEFT JOIN cosmetic_tags t ON t.id = o.tag_id
            ORDER BY t.sort_order, t.name, t.id
            """;

    private final HikariDataSource dataSource;

    public record MutationResult(Status status, TagSnapshot snapshot, boolean changed) {}

    /** Schema/seed failures propagate so callers can keep the feature unavailable. */
    public TagRepository(HikariDataSource dataSource, Logger logger) throws SQLException {
        this.dataSource = Objects.requireNonNull(dataSource);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    for (String sql : SCHEMA_SQL) statement.execute(sql);
                }
                int defaults = seedDefinitions(connection);
                connection.commit();
                logger.info("Initialised collectible tag storage; checked {} bundled default definitions", defaults);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    public TagSnapshot load(UUID playerId) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return snapshot(connection, playerId);
        }
    }

    public MutationResult select(UUID playerId, String tagId, long expectedRevision) throws SQLException {
        return mutate(playerId, (connection, before) -> {
            if (before.revision() != expectedRevision) return Status.STALE_STATE;
            // A disabled saved choice remains dormant until the player explicitly replaces it.
            if (Objects.equals(tagId, before.selectedTagId())) return Status.OK;
            if (tagId != null) {
                Boolean enabled = definitionEnabled(connection, tagId);
                if (enabled == null) return Status.UNKNOWN_TAG;
                if (!enabled) return Status.DISABLED;
                if (before.ownedTags().stream().noneMatch(tag -> tag.id().equals(tagId))) return Status.NOT_OWNED;
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE player_tag_state SET selected_tag_id = ?, revision = revision + 1,
                        updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER WHERE minecraft_uuid = ?
                    """)) {
                if (tagId == null) statement.setNull(1, Types.VARCHAR); else statement.setString(1, tagId);
                statement.setString(2, playerId.toString());
                statement.executeUpdate();
            }
            return Status.OK;
        });
    }

    public MutationResult grant(UUID playerId, String tagId, String source, String actor, String reason)
            throws SQLException {
        return mutate(playerId, (connection, before) -> {
            if (definitionEnabled(connection, tagId) == null) return Status.UNKNOWN_TAG;
            int inserted;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO player_tags (minecraft_uuid, tag_id, granted_at, source, granted_by)
                    VALUES (?, ?, EXTRACT(EPOCH FROM NOW())::INTEGER, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, tagId);
                statement.setString(3, source);
                statement.setString(4, actor);
                inserted = statement.executeUpdate();
            }
            if (inserted != 0) {
                audit(connection, playerId, tagId, "grant", actor, source, reason);
                incrementRevision(connection, playerId);
            }
            return Status.OK;
        });
    }

    public MutationResult revoke(UUID playerId, String tagId, String actor, String reason) throws SQLException {
        return mutate(playerId, (connection, before) -> {
            if (definitionEnabled(connection, tagId) == null) return Status.UNKNOWN_TAG;
            if (before.ownedTags().stream().noneMatch(tag -> tag.id().equals(tagId))) return Status.OK;
            // Clear before deletion: the composite ownership FK deliberately uses NO ACTION.
            try (var statement = connection.prepareStatement("""
                    UPDATE player_tag_state SET selected_tag_id = NULL
                    WHERE minecraft_uuid = ? AND selected_tag_id = ?
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, tagId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "DELETE FROM player_tags WHERE minecraft_uuid = ? AND tag_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, tagId);
                statement.executeUpdate();
            }
            audit(connection, playerId, tagId, "revoke", actor, "manual", reason);
            incrementRevision(connection, playerId);
            return Status.OK;
        });
    }

    private MutationResult mutate(UUID playerId, Mutation mutation) throws SQLException {
        Objects.requireNonNull(playerId);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO player_tag_state (minecraft_uuid, updated_at)
                        VALUES (?, EXTRACT(EPOCH FROM NOW())::INTEGER) ON CONFLICT DO NOTHING
                        """)) {
                    statement.setString(1, playerId.toString());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                        "SELECT revision FROM player_tag_state WHERE minecraft_uuid = ? FOR UPDATE")) {
                    statement.setString(1, playerId.toString());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Player tag state disappeared while locking");
                    }
                }
                TagSnapshot before = snapshot(connection, playerId);
                Status status = mutation.apply(connection, before);
                TagSnapshot after = snapshot(connection, playerId);
                connection.commit();
                return new MutationResult(status, after, after.revision() != before.revision());
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private static TagSnapshot snapshot(Connection connection, UUID playerId) throws SQLException {
        try (var statement = connection.prepareStatement(SNAPSHOT_SQL)) {
            statement.setString(1, playerId.toString());
            try (var rows = statement.executeQuery()) {
                long revision = 0;
                String selectedTagId = null;
                var ownedTags = new ArrayList<TagDefinition>();
                while (rows.next()) {
                    revision = rows.getLong("revision");
                    selectedTagId = rows.getString("selected_tag_id");
                    String id = rows.getString("id");
                    if (id != null) ownedTags.add(new TagDefinition(id, rows.getString("name"),
                            rows.getString("description"), rows.getString("glyph_id"),
                            rows.getInt("sort_order"), rows.getBoolean("enabled")));
                }
                return new TagSnapshot(playerId, revision, selectedTagId, ownedTags);
            }
        }
    }

    private static Boolean definitionEnabled(Connection connection, String tagId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT enabled FROM cosmetic_tags WHERE id = ?")) {
            statement.setString(1, tagId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getBoolean(1) : null;
            }
        }
    }

    private static void incrementRevision(Connection connection, UUID playerId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE player_tag_state SET revision = revision + 1, updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
                WHERE minecraft_uuid = ?
                """)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    private static void audit(Connection connection, UUID playerId, String tagId, String action,
                              String actor, String source, String reason) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO player_tag_audit
                    (id, minecraft_uuid, tag_id, action, ownership_changed, actor, source, reason, created_at)
                VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, EXTRACT(EPOCH FROM NOW())::INTEGER)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, tagId);
            statement.setString(4, action);
            statement.setString(5, actor);
            statement.setString(6, source);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static int seedDefinitions(Connection connection) throws SQLException {
        var resource = TagRepository.class.getResourceAsStream("/crabcraft/tags.json");
        if (resource == null) throw new SQLException("Bundled tag catalogue /crabcraft/tags.json is missing");
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8);
             var statement = connection.prepareStatement("""
                     INSERT INTO cosmetic_tags (id, name, description, glyph_id, sort_order, enabled)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT (id) DO NOTHING
                     """)) {
            var definitions = JsonParser.parseReader(reader).getAsJsonArray();
            for (var element : definitions) {
                var tag = element.getAsJsonObject();
                statement.setString(1, tag.get("id").getAsString());
                statement.setString(2, tag.get("name").getAsString());
                statement.setString(3, tag.get("description").getAsString());
                statement.setString(4, tag.get("glyph_id").getAsString());
                statement.setInt(5, tag.get("sort_order").getAsInt());
                statement.setBoolean(6, tag.get("enabled").getAsBoolean());
                statement.addBatch();
            }
            statement.executeBatch();
            return definitions.size();
        } catch (IOException | RuntimeException failure) {
            throw new SQLException("Could not load bundled tag catalogue", failure);
        }
    }

    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface Mutation {
        Status apply(Connection connection, TagSnapshot before) throws SQLException;
    }
}
