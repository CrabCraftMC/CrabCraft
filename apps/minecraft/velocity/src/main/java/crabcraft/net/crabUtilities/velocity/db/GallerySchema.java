package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Keeps the Java-side database bootstrap in lockstep with the Gallery tables
 * declared in {@code packages/db/src/schema.ts}. Velocity does not query these
 * tables; creating them here prevents either runtime from seeing schema drift.
 */
public final class GallerySchema {

    private static final String[] CREATE_SCHEMA_SQL = {
            """
            CREATE SEQUENCE IF NOT EXISTS gallery_sync_revision_seq
                INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807
                START WITH 1 CACHE 1
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_post_sync_state (
                thread_id TEXT PRIMARY KEY,
                last_revision BIGINT NOT NULL,
                CONSTRAINT gallery_post_sync_state_revision_check
                    CHECK (last_revision > 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_channel_sync_state (
                channel_id TEXT PRIMARY KEY,
                tags_revision BIGINT NOT NULL DEFAULT 0,
                tags_hash TEXT NOT NULL DEFAULT '',
                deleted_revision BIGINT,
                CONSTRAINT gallery_channel_sync_state_tags_revision_check
                    CHECK (tags_revision >= 0),
                CONSTRAINT gallery_channel_sync_state_deleted_revision_check
                    CHECK (deleted_revision IS NULL OR deleted_revision > 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_posts (
                thread_id TEXT PRIMARY KEY,
                channel_id TEXT NOT NULL,
                season_id TEXT NOT NULL,
                season_number INTEGER NOT NULL,
                title TEXT NOT NULL,
                content TEXT,
                author_discord_id TEXT NOT NULL,
                author_discord_username TEXT NOT NULL,
                author_display_name TEXT NOT NULL,
                author_webhook_id TEXT,
                source_url TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                edited_at INTEGER,
                content_hash TEXT NOT NULL,
                content_updated_at INTEGER NOT NULL,
                archived BOOLEAN NOT NULL DEFAULT FALSE,
                locked BOOLEAN NOT NULL DEFAULT FALSE,
                pinned BOOLEAN NOT NULL DEFAULT FALSE,
                published BOOLEAN NOT NULL DEFAULT TRUE,
                published_at INTEGER NOT NULL,
                deleted_at INTEGER,
                last_synced_at INTEGER NOT NULL,
                reactions_revision BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT gallery_posts_season_number_check
                    CHECK (season_number > 0),
                CONSTRAINT gallery_posts_reactions_revision_check
                    CHECK (reactions_revision >= 0)
            )
            """,
            """
            ALTER TABLE gallery_posts
                ADD COLUMN IF NOT EXISTS reactions_revision BIGINT NOT NULL DEFAULT 0
            """,
            """
            ALTER TABLE gallery_posts
                ADD COLUMN IF NOT EXISTS content_hash TEXT
            """,
            """
            UPDATE gallery_posts
            SET content_hash = ''
            WHERE content_hash IS NULL
            """,
            """
            ALTER TABLE gallery_posts
                ALTER COLUMN content_hash SET NOT NULL
            """,
            """
            ALTER TABLE gallery_posts
                ADD COLUMN IF NOT EXISTS content_updated_at INTEGER
            """,
            """
            UPDATE gallery_posts
            SET content_updated_at = COALESCE(edited_at, posted_at)
            WHERE content_updated_at IS NULL
            """,
            """
            ALTER TABLE gallery_posts
                ALTER COLUMN content_updated_at SET NOT NULL
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_tags (
                discord_tag_id TEXT PRIMARY KEY,
                channel_id TEXT NOT NULL,
                name TEXT NOT NULL,
                emoji_id TEXT,
                emoji_name TEXT,
                moderated BOOLEAN NOT NULL DEFAULT FALSE,
                available BOOLEAN NOT NULL DEFAULT TRUE,
                position INTEGER NOT NULL,
                last_synced_at INTEGER NOT NULL,
                CONSTRAINT gallery_tags_position_check CHECK (position >= 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_images (
                discord_attachment_id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL,
                storage_key TEXT NOT NULL,
                public_url TEXT NOT NULL,
                filename TEXT NOT NULL,
                alt TEXT,
                content_type TEXT,
                size INTEGER NOT NULL,
                width INTEGER,
                height INTEGER,
                position INTEGER NOT NULL,
                CONSTRAINT gallery_images_post_id_gallery_posts_thread_id_fk
                    FOREIGN KEY (post_id) REFERENCES gallery_posts(thread_id)
                    ON DELETE CASCADE,
                CONSTRAINT gallery_images_storage_key_unique UNIQUE (storage_key),
                CONSTRAINT gallery_images_position_check CHECK (position >= 0),
                CONSTRAINT gallery_images_size_check CHECK (size >= 0),
                CONSTRAINT gallery_images_width_check
                    CHECK (width IS NULL OR width > 0),
                CONSTRAINT gallery_images_height_check
                    CHECK (height IS NULL OR height > 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_storage_deletions (
                storage_key TEXT PRIMARY KEY,
                public_url TEXT NOT NULL,
                queued_at INTEGER NOT NULL,
                delete_after INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_attempt_at INTEGER,
                last_error TEXT,
                CONSTRAINT gallery_storage_deletions_attempts_check
                    CHECK (attempts >= 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_reactions (
                post_id TEXT NOT NULL,
                emoji_key TEXT NOT NULL,
                emoji_id TEXT,
                emoji_name TEXT NOT NULL,
                animated BOOLEAN NOT NULL DEFAULT FALSE,
                count INTEGER NOT NULL,
                CONSTRAINT gallery_reactions_post_id_emoji_key_pk
                    PRIMARY KEY (post_id, emoji_key),
                CONSTRAINT gallery_reactions_post_id_gallery_posts_thread_id_fk
                    FOREIGN KEY (post_id) REFERENCES gallery_posts(thread_id)
                    ON DELETE CASCADE,
                CONSTRAINT gallery_reactions_count_check CHECK (count > 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS gallery_post_tags (
                post_id TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                CONSTRAINT gallery_post_tags_post_id_tag_id_pk
                    PRIMARY KEY (post_id, tag_id),
                CONSTRAINT gallery_post_tags_post_id_gallery_posts_thread_id_fk
                    FOREIGN KEY (post_id) REFERENCES gallery_posts(thread_id)
                    ON DELETE CASCADE,
                CONSTRAINT gallery_post_tags_tag_id_gallery_tags_discord_tag_id_fk
                    FOREIGN KEY (tag_id) REFERENCES gallery_tags(discord_tag_id)
                    ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_posts_published_date_idx
                ON gallery_posts (posted_at, thread_id)
                WHERE published = TRUE AND deleted_at IS NULL
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_posts_season_date_idx
                ON gallery_posts (season_number, posted_at, thread_id)
                WHERE published = TRUE AND deleted_at IS NULL
            """,
            """
            DROP INDEX IF EXISTS gallery_posts_channel_sync_idx
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_posts_content_updated_idx
                ON gallery_posts (content_updated_at, thread_id)
                WHERE published = TRUE AND deleted_at IS NULL
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_posts_channel_idx
                ON gallery_posts (channel_id, thread_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_posts_author_idx
                ON gallery_posts (author_discord_id)
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS gallery_images_post_position_unique
                ON gallery_images (post_id, position)
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_storage_deletions_due_idx
                ON gallery_storage_deletions (delete_after, storage_key)
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_tags_channel_available_idx
                ON gallery_tags (channel_id, available, position)
            """,
            """
            CREATE INDEX IF NOT EXISTS gallery_post_tags_tag_idx
                ON gallery_post_tags (tag_id, post_id)
            """
    };

    private GallerySchema() {
    }

    public static void ensure(HikariDataSource dataSource, Logger logger) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String sql : CREATE_SCHEMA_SQL) {
                    statement.execute(sql);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            logger.error("Failed to ensure Gallery database schema", exception);
        }
    }
}
