import {
  pgTable,
  pgEnum,
  text,
  integer,
  bigint,
  boolean,
  serial,
  real,
  jsonb,
  uniqueIndex,
  index,
  primaryKey,
  check,
  pgSequence,
} from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";

export const playerRoleEnum = pgEnum("player_role", [
  "unverified",
  "verified",
  "moderator",
  "admin",
]);

export type PlayerRole = (typeof playerRoleEnum.enumValues)[number];

// ── players ─────────────────────────────────────────────────────
export const players = pgTable("players", {
  discord_id: text("discord_id").primaryKey(),
  discord_username: text("discord_username").notNull(),
  minecraft_username: text("minecraft_username"),
  minecraft_uuid: text("minecraft_uuid").unique(),
  nickname: text("nickname"),
  nickname_raw: text("nickname_raw"),
  role: playerRoleEnum("role").notNull().default("unverified"),
  // Reconciled from the configured Discord guild on every bot startup and
  // updated immediately by guildMemberAdd/guildMemberRemove events. Historical
  // stats remain stored when a member leaves, but ranking queries exclude them.
  is_discord_member: boolean("is_discord_member").notNull().default(true),
  created_at: integer("created_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
  updated_at: integer("updated_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
  last_login_at: integer("last_login_at"),
  last_mc_login_at: integer("last_mc_login_at"),
});

// ── applications ────────────────────────────────────────────────
export const applications = pgTable(
  "applications",
  {
    id: serial("id").primaryKey(),
    discord_id: text("discord_id")
      .notNull()
      .references(() => players.discord_id),
    discord_username: text("discord_username").notNull(),
    minecraft_username: text("minecraft_username").notNull(),
    minecraft_uuid: text("minecraft_uuid"),
    age: integer("age"),
    age_met: boolean("age_met").notNull(),
    // Legacy fields retained so existing application history is not dropped.
    voice_chat: boolean("voice_chat").notNull(),
    policy_agreed: boolean("policy_agreed").notNull().default(false),
    status: text("status").notNull().default("pending"),
    join_reason: text("join_reason"),
    favourite_wood: text("favourite_wood"),
    about_you: text("about_you"),
    referral_source: text("referral_source"),
    denial_reason: text("denial_reason"),
    season: text("season"),
    applied_at: integer("applied_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
    resolved_at: integer("resolved_at"),
    resolved_by_discord_id: text("resolved_by_discord_id"),
  },
  (table) => [
    uniqueIndex("applications_discord_season_unique").on(
      table.discord_id,
      table.season,
    ),
  ],
);

// ── seasons ─────────────────────────────────────────────────────
export const seasons = pgTable("seasons", {
  id: text("id").primaryKey(),
  name: text("name").notNull(),
  start_date: text("start_date"),
  end_date: text("end_date"),
  is_current: boolean("is_current").notNull().default(false),
  created_at: integer("created_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
});

// ── player_season_stats ─────────────────────────────────────────
export const playerSeasonStats = pgTable(
  "player_season_stats",
  {
    id: serial("id").primaryKey(),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    season: text("season").notNull(),
    play_time_seconds: integer("play_time_seconds").notNull().default(0),
    walk_distance_m: real("walk_distance_m").notNull().default(0),
    sprint_distance_m: real("sprint_distance_m").notNull().default(0),
    swim_distance_m: real("swim_distance_m").notNull().default(0),
    fly_distance_m: real("fly_distance_m").notNull().default(0),
    boat_distance_m: real("boat_distance_m").notNull().default(0),
    elytra_distance_m: real("elytra_distance_m").notNull().default(0),
    horse_distance_m: real("horse_distance_m").notNull().default(0),
    climb_distance_m: real("climb_distance_m").notNull().default(0),
    fall_distance_m: real("fall_distance_m").notNull().default(0),
    total_distance_m: real("total_distance_m").notNull().default(0),
    mob_kills: integer("mob_kills").notNull().default(0),
    player_kills: integer("player_kills").notNull().default(0),
    deaths: integer("deaths").notNull().default(0),
    damage_dealt: integer("damage_dealt").notNull().default(0),
    damage_taken: integer("damage_taken").notNull().default(0),
    total_blocks_mined: integer("total_blocks_mined").notNull().default(0),
    total_blocks_placed: integer("total_blocks_placed").notNull().default(0),
    total_items_crafted: integer("total_items_crafted").notNull().default(0),
    total_items_broken: integer("total_items_broken").notNull().default(0),
    jumps: integer("jumps").notNull().default(0),
    animals_bred: integer("animals_bred").notNull().default(0),
    fish_caught: integer("fish_caught").notNull().default(0),
    villagers_traded: integer("villagers_traded").notNull().default(0),
    enchantments: integer("enchantments").notNull().default(0),
    times_slept: integer("times_slept").notNull().default(0),
    top_block_mined: text("top_block_mined"),
    top_mob_killed: text("top_mob_killed"),
    top_item_crafted: text("top_item_crafted"),
    top_item_used: text("top_item_used"),
    top_death_cause: text("top_death_cause"),
    computed_at: integer("computed_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    uniqueIndex("pss_uuid_season_unique").on(
      table.minecraft_uuid,
      table.season,
    ),
    index("pss_season_idx").on(table.season),
  ],
);

// ── awards ──────────────────────────────────────────────────────
// Award definitions. Runtime-editable: admins can add/rename/disable
// awards without redeploying. Evaluated by the Velocity plugin against
// each player's vanilla stats and optional plugin-provided metrics.
export const awards = pgTable("awards", {
  id: text("id").primaryKey(),
  title: text("title").notNull(),
  description: text("description").notNull().default(""),
  unit: text("unit").notNull(), // int | ticks | cm | tenths_of_heart
  bucket: text("bucket").notNull(), // combat | mining | crafting | building | items | food | movement | misc
  icon: text("icon").notNull(),
  // Reader spec used by the plugin to extract a value. Standard readers
  // mirror MinecraftStats; custom-int reads optional plugin metrics.
  reader_type: text("reader_type").notNull(), // int | match-sum | custom-int
  reader_path: jsonb("reader_path").notNull(), // string[]
  reader_patterns: jsonb("reader_patterns"), // string[] | null
  enabled: boolean("enabled").notNull().default(true),
  sort_order: integer("sort_order").notNull().default(0),
  created_at: integer("created_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
  updated_at: integer("updated_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
});

// ── player_award_scores ─────────────────────────────────────────
// Per-player, per-award score for a season.
export const playerAwardScores = pgTable(
  "player_award_scores",
  {
    id: serial("id").primaryKey(),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    season: text("season").notNull(),
    award_id: text("award_id")
      .notNull()
      .references(() => awards.id, { onDelete: "cascade" }),
    score: real("score").notNull().default(0),
    medal: integer("medal").notNull().default(0), // 0 = none, 1 = gold, 2 = silver, 3 = bronze
    computed_at: integer("computed_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    uniqueIndex("pas_uuid_season_award_unique").on(
      table.minecraft_uuid,
      table.season,
      table.award_id,
    ),
    index("pas_leaderboard_idx").on(
      table.season,
      table.award_id,
      table.score,
    ),
    index("pas_player_idx").on(table.minecraft_uuid, table.season),
  ],
);

// Crown scores (gold/silver/bronze totals + weighted crown score) are
// not stored — they're aggregated on read directly from
// player_award_scores. See getCrownLeaderboard / getPlayerCrownScore
// in packages/db/src/queries/web.ts.

// ── player_advancements ────────────────────────────────────────
// Per-player, per-advancement completion state for a season.
export const playerAdvancements = pgTable(
  "player_advancements",
  {
    id: serial("id").primaryKey(),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    season: text("season").notNull(),
    advancement_id: text("advancement_id").notNull(),
    completed: boolean("completed").notNull().default(false),
    completed_at: integer("completed_at"),
  },
  (table) => [
    uniqueIndex("pa_uuid_season_adv_unique").on(
      table.minecraft_uuid,
      table.season,
      table.advancement_id,
    ),
    index("pa_leaderboard_idx").on(
      table.season,
      table.completed,
    ),
    index("pa_player_idx").on(table.minecraft_uuid, table.season),
  ],
);

// ── stream_channels ────────────────────────────────────────────
// Monitored streaming channels for the Discord bot. When a channel
// goes live, the linked Discord user receives a "live" role.
export const streamChannels = pgTable(
  "stream_channels",
  {
    id: serial("id").primaryKey(),
    platform: text("platform").notNull(), // youtube | twitch | tiktok
    channel_id: text("channel_id").notNull(),
    discord_user_id: text("discord_user_id").notNull(),
    display_name: text("display_name"),
  },
  (table) => [
    uniqueIndex("sc_platform_channel_unique").on(
      table.platform,
      table.channel_id,
    ),
  ],
);

// ── block_gradient_shares ─────────────────────────────────────
// Immutable, public snapshots created by the Block Gradient web tool.
// The short ID is derived from the normalised state, so saving an identical
// recipe reuses the existing row.
export const blockGradientShares = pgTable("block_gradient_shares", {
  id: text("id").primaryKey(),
  version: integer("version").notNull().default(1),
  state: jsonb("state").$type<Record<string, unknown>>().notNull(),
  created_at: integer("created_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
});

// ── mc_login_history ───────────────────────────────────────────
// Tracks every Minecraft login by UUID, independent of Discord
// verification status. Used by the Velocity proxy to decide
// whether a connecting player should see the "first join"
// message — works for unverified players who have no row in
// `players` or `player_alts`.
export const mcLoginHistory = pgTable("mc_login_history", {
  minecraft_uuid: text("minecraft_uuid").primaryKey(),
  first_seen_at: integer("first_seen_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
  last_seen_at: integer("last_seen_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
});

// ── player_settings ────────────────────────────────────────────
// Canonical per-player Minecraft settings written by the Velocity proxy.
// `settings` remains text because Java stores the serialized JSON verbatim
// so new preferences do not require a database migration.
export const playerSettings = pgTable("player_settings", {
  minecraft_uuid: text("minecraft_uuid").primaryKey(),
  settings: text("settings").notNull(),
  updated_at: integer("updated_at").notNull(),
});

// ── weekly bingo ───────────────────────────────────────────────
// Cards are prepared in advance. Paper receives the currently active card via
// Redis while the Discord bot owns scheduling, rendering and durable progress.
export const bingoCards = pgTable(
  "bingo_cards",
  {
    id: serial("id").primaryKey(),
    number: integer("number").notNull(),
    starts_at: integer("starts_at").notNull(),
    ends_at: integer("ends_at").notNull(),
    tasks: jsonb("tasks").$type<Array<{ id: string; label: string }>>().notNull(),
    announcement_guild_id: text("announcement_guild_id"),
    announcement_channel_id: text("announcement_channel_id"),
    announcement_message_id: text("announcement_message_id"),
    posted_at: integer("posted_at"),
    created_at: integer("created_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    uniqueIndex("bingo_cards_number_unique").on(table.number),
    index("bingo_cards_active_idx").on(table.starts_at, table.ends_at),
  ],
);

export const bingoPlayerProgress = pgTable(
  "bingo_player_progress",
  {
    card_id: integer("card_id")
      .notNull()
      .references(() => bingoCards.id, { onDelete: "cascade" }),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    source_minecraft_uuid: text("source_minecraft_uuid").notNull(),
    task_id: text("task_id").notNull(),
    completed_at: integer("completed_at").notNull(),
    source_backend: text("source_backend"),
  },
  (table) => [
    primaryKey({ columns: [table.card_id, table.minecraft_uuid, table.task_id] }),
    index("bingo_progress_player_idx").on(table.minecraft_uuid, table.card_id),
  ],
);

export const bingoPlayerMilestones = pgTable(
  "bingo_player_milestones",
  {
    card_id: integer("card_id")
      .notNull()
      .references(() => bingoCards.id, { onDelete: "cascade" }),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    first_line_completed_at: integer("first_line_completed_at"),
    first_line_announced_at: integer("first_line_announced_at"),
    first_line_role_awarded_at: integer("first_line_role_awarded_at"),
    blackout_completed_at: integer("blackout_completed_at"),
    blackout_announced_at: integer("blackout_announced_at"),
    blackout_role_awarded_at: integer("blackout_role_awarded_at"),
  },
  (table) => [
    primaryKey({ columns: [table.card_id, table.minecraft_uuid] }),
    index("bingo_milestones_pending_idx").on(
      table.first_line_announced_at,
      table.blackout_announced_at,
    ),
  ],
);

// ── player_login_streaks ───────────────────────────────────────
// All-time login streaks per Minecraft account. Updated by the
// Velocity proxy after a player has been online long enough to qualify
// for the streak day. A "day" is a fixed 24-hour window rolling over at
// a configured hour (06:00 UTC by default). A qualified day adds +1; a
// single missed day is forgiven (streak holds, no point); two missed
// days in a row reset it to 1. Alt accounts (rows in `player_alts`)
// are capped at a one-day streak and excluded from the leaderboard.
export const playerLoginStreaks = pgTable(
  "player_login_streaks",
  {
    minecraft_uuid: text("minecraft_uuid").primaryKey(),
    current_streak: integer("current_streak").notNull().default(0),
    longest_streak: integer("longest_streak").notNull().default(0),
    last_login_at: integer("last_login_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
    streak_started_at: integer("streak_started_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
    updated_at: integer("updated_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    index("pls_current_streak_idx").on(table.current_streak),
    index("pls_longest_streak_idx").on(table.longest_streak),
  ],
);

// ── player_login_streak_progress ────────────────────────────────
// Cumulative online seconds toward qualifying for a login streak day.
// The Velocity proxy records each session segment here and only updates
// player_login_streaks when accumulated_seconds reaches the configured
// daily requirement.
export const playerLoginStreakProgress = pgTable(
  "player_login_streak_progress",
  {
    minecraft_uuid: text("minecraft_uuid").notNull(),
    streak_day: bigint("streak_day", { mode: "number" }).notNull(),
    accumulated_seconds: integer("accumulated_seconds").notNull().default(0),
    qualified_at: integer("qualified_at"),
    updated_at: integer("updated_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    primaryKey({
      name: "player_login_streak_progress_minecraft_uuid_streak_day_pk",
      columns: [table.minecraft_uuid, table.streak_day],
    }),
  ],
);

// ── starboard_posts ────────────────────────────────────────────
// Every Discord message reposted to the starboard. Used to dedupe
// across bot restarts and to power per-user starboard queries
// ("show me all of @user's starred posts").
export const starboardPosts = pgTable(
  "starboard_posts",
  {
    message_id: text("message_id").primaryKey(),
    channel_id: text("channel_id").notNull(),
    author_id: text("author_id").notNull(),
    starboard_message_id: text("starboard_message_id"),
    // Emoji that crossed the threshold and should be displayed on the
    // starboard repost. `trigger_emoji_id` is null for native unicode
    // emojis; for guild custom emojis it holds the Discord emoji ID.
    trigger_emoji_id: text("trigger_emoji_id"),
    trigger_emoji_name: text("trigger_emoji_name"),
    trigger_emoji_animated: boolean("trigger_emoji_animated")
      .notNull()
      .default(false),
    posted_at: integer("posted_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    index("starboard_author_idx").on(table.author_id),
  ],
);

// ── counting_state ─────────────────────────────────────────────
// Per-channel current count for the counting channel feature.
// Single row per channel; the bot only reads/updates the row whose
// channel_id matches the configured counting channel.
export const countingState = pgTable("counting_state", {
  channel_id: text("channel_id").primaryKey(),
  current_count: integer("current_count").notNull().default(0),
  last_user_id: text("last_user_id"),
  updated_at: integer("updated_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
});

// ── player_alts ────────────────────────────────────────────────
// Alt Minecraft accounts linked by whitelisted players via the
// Discord bot. Velocity checks this table on each proxy join to
// assign the LuckPerms "alt" group.
export const playerAlts = pgTable(
  "player_alts",
  {
    id: serial("id").primaryKey(),
    discord_id: text("discord_id")
      .notNull()
      .references(() => players.discord_id),
    minecraft_uuid: text("minecraft_uuid").notNull().unique(),
    minecraft_username: text("minecraft_username").notNull(),
    created_at: integer("created_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    index("palt_discord_idx").on(table.discord_id),
  ],
);

// ── tickets ────────────────────────────────────────────────────
export const ticketCategoryEnum = pgEnum("ticket_category", [
  "general",
  "council",
  "grief",
  "appeal",
]);

export type TicketCategory = (typeof ticketCategoryEnum.enumValues)[number];

export const ticketStatusEnum = pgEnum("ticket_status", [
  "open",
  "closed",
]);

export type TicketStatus = (typeof ticketStatusEnum.enumValues)[number];

// Tickets opened via the Discord ticket embed. Each ticket is a
// dedicated text channel under the configured ticket category;
// lifecycle is open → closed → deleted (after grace window).
// `intake` holds the category-specific modal fields submitted by the
// opener so staff have full context without scrolling the transcript.
export const tickets = pgTable(
  "tickets",
  {
    id: serial("id").primaryKey(),
    channel_id: text("channel_id").notNull().unique(),
    parent_category_id: text("parent_category_id").notNull(),
    guild_id: text("guild_id").notNull(),

    opener_discord_id: text("opener_discord_id").notNull(),
    opener_discord_username: text("opener_discord_username").notNull(),
    opener_minecraft_uuid: text("opener_minecraft_uuid"),
    opener_minecraft_username: text("opener_minecraft_username"),

    category: ticketCategoryEnum("category").notNull(),
    status: ticketStatusEnum("status").notNull().default("open"),

    subject: text("subject"),
    intake: jsonb("intake"),

    closed_by_discord_id: text("closed_by_discord_id"),
    closed_at: integer("closed_at"),
    // Unix seconds; closed channels are deleted at this point unless reopened.
    delete_after: integer("delete_after"),

    created_at: integer("created_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
    updated_at: integer("updated_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    index("tickets_opener_idx").on(table.opener_discord_id, table.status),
    index("tickets_status_idx").on(table.status),
    index("tickets_delete_after_idx").on(table.delete_after),
  ],
);

// ── application_channels ────────────────────────────────────────
// One private text channel per applicant, created on join under the
// configured application category. This table is the source of truth for
// channel↔applicant identity, the "you haven't applied yet" reminder flag,
// and the post-decision deletion window — replacing the old approach of
// packing that state into the channel `topic` string. A restart-safe
// cleanup scan deletes channels once `delete_after` (unix seconds) passes.
export const applicationChannels = pgTable(
  "application_channels",
  {
    channel_id: text("channel_id").primaryKey(),
    applicant_id: text("applicant_id").notNull(),
    applicant_username: text("applicant_username").notNull(),
    guild_id: text("guild_id").notNull(),
    reminded: boolean("reminded").notNull().default(false),
    // Unix seconds; set when the application is accepted/denied. The channel
    // is removed at this point by the periodic + startup cleanup scans.
    delete_after: integer("delete_after"),
    created_at: integer("created_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
    updated_at: integer("updated_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    index("appchannels_applicant_idx").on(table.applicant_id),
    index("appchannels_delete_after_idx").on(table.delete_after),
  ],
);

// ── gallery_posts ─────────────────────────────────────────────
export const gallerySyncRevisionSeq = pgSequence("gallery_sync_revision_seq");

// Per-post revisions remain after a post disappears, preventing an older
// Gateway event or reconciliation snapshot from resurrecting it.
export const galleryPostSyncState = pgTable(
  "gallery_post_sync_state",
  {
    thread_id: text("thread_id").primaryKey(),
    last_revision: bigint("last_revision", { mode: "number" }).notNull(),
  },
  (table) => [
    check(
      "gallery_post_sync_state_revision_check",
      sql`${table.last_revision} > 0`,
    ),
  ],
);

// Tag and post revisions are independent: a newer catalogue rename does not
// make a force-fetched message edit stale. Post paths lock this row only to
// observe deleted_revision; tags_revision exclusively CASes tag catalogues.
export const galleryChannelSyncState = pgTable(
  "gallery_channel_sync_state",
  {
    channel_id: text("channel_id").primaryKey(),
    tags_revision: bigint("tags_revision", { mode: "number" })
      .notNull()
      .default(0),
    tags_hash: text("tags_hash").notNull().default(""),
    deleted_revision: bigint("deleted_revision", { mode: "number" }),
  },
  (table) => [
    check(
      "gallery_channel_sync_state_tags_revision_check",
      sql`${table.tags_revision} >= 0`,
    ),
    check(
      "gallery_channel_sync_state_deleted_revision_check",
      sql`${table.deleted_revision} IS NULL OR ${table.deleted_revision} > 0`,
    ),
  ],
);

// Durable copy of Discord media-channel posts. Discord attachment URLs are
// deliberately not stored: the bot first copies each image to gallery media
// storage, then persists its stable storage key and public URL below.
export const galleryPosts = pgTable(
  "gallery_posts",
  {
    thread_id: text("thread_id").primaryKey(),
    channel_id: text("channel_id").notNull(),
    season_id: text("season_id").notNull(),
    season_number: integer("season_number").notNull(),
    title: text("title").notNull(),
    content: text("content"),
    author_discord_id: text("author_discord_id").notNull(),
    author_discord_username: text("author_discord_username").notNull(),
    author_display_name: text("author_display_name").notNull(),
    author_webhook_id: text("author_webhook_id"),
    source_url: text("source_url").notNull(),
    posted_at: integer("posted_at").notNull(),
    edited_at: integer("edited_at"),
    content_hash: text("content_hash").notNull(),
    content_updated_at: integer("content_updated_at").notNull(),
    archived: boolean("archived").notNull().default(false),
    locked: boolean("locked").notNull().default(false),
    pinned: boolean("pinned").notNull().default(false),
    published: boolean("published").notNull().default(true),
    published_at: integer("published_at").notNull(),
    deleted_at: integer("deleted_at"),
    last_synced_at: integer("last_synced_at").notNull(),
    reactions_revision: bigint("reactions_revision", { mode: "number" })
      .notNull()
      .default(0),
  },
  (table) => [
    check("gallery_posts_season_number_check", sql`${table.season_number} > 0`),
    check(
      "gallery_posts_reactions_revision_check",
      sql`${table.reactions_revision} >= 0`,
    ),
    index("gallery_posts_published_date_idx")
      .on(table.posted_at, table.thread_id)
      .where(sql`${table.published} = true AND ${table.deleted_at} IS NULL`),
    index("gallery_posts_season_date_idx")
      .on(table.season_number, table.posted_at, table.thread_id)
      .where(sql`${table.published} = true AND ${table.deleted_at} IS NULL`),
    index("gallery_posts_content_updated_idx")
      .on(table.content_updated_at, table.thread_id)
      .where(sql`${table.published} = true AND ${table.deleted_at} IS NULL`),
    index("gallery_posts_channel_idx").on(table.channel_id, table.thread_id),
    index("gallery_posts_author_idx").on(table.author_discord_id),
  ],
);

// ── gallery_images ─────────────────────────────────────────────
export const galleryImages = pgTable(
  "gallery_images",
  {
    discord_attachment_id: text("discord_attachment_id").primaryKey(),
    post_id: text("post_id")
      .notNull()
      .references(() => galleryPosts.thread_id, { onDelete: "cascade" }),
    storage_key: text("storage_key").notNull().unique(),
    public_url: text("public_url").notNull(),
    filename: text("filename").notNull(),
    alt: text("alt"),
    content_type: text("content_type"),
    size: integer("size").notNull(),
    width: integer("width"),
    height: integer("height"),
    position: integer("position").notNull(),
  },
  (table) => [
    uniqueIndex("gallery_images_post_position_unique").on(
      table.post_id,
      table.position,
    ),
    check("gallery_images_position_check", sql`${table.position} >= 0`),
    check("gallery_images_size_check", sql`${table.size} >= 0`),
    check("gallery_images_width_check", sql`${table.width} IS NULL OR ${table.width} > 0`),
    check("gallery_images_height_check", sql`${table.height} IS NULL OR ${table.height} > 0`),
  ],
);

// ── gallery_reactions ──────────────────────────────────────────
export const galleryReactions = pgTable(
  "gallery_reactions",
  {
    post_id: text("post_id")
      .notNull()
      .references(() => galleryPosts.thread_id, { onDelete: "cascade" }),
    emoji_key: text("emoji_key").notNull(),
    emoji_id: text("emoji_id"),
    emoji_name: text("emoji_name").notNull(),
    animated: boolean("animated").notNull().default(false),
    count: integer("count").notNull(),
  },
  (table) => [
    primaryKey({ columns: [table.post_id, table.emoji_key] }),
    check("gallery_reactions_count_check", sql`${table.count} > 0`),
  ],
);

// Durable, retryable work queue for media no longer referenced by a post.
// Upserts cancel rows when the same immutable storage key becomes current
// again before its grace period or an active deletion lease expires.
export const galleryStorageDeletions = pgTable(
  "gallery_storage_deletions",
  {
    storage_key: text("storage_key").primaryKey(),
    public_url: text("public_url").notNull(),
    queued_at: integer("queued_at").notNull(),
    delete_after: integer("delete_after").notNull(),
    attempts: integer("attempts").notNull().default(0),
    last_attempt_at: integer("last_attempt_at"),
    last_error: text("last_error"),
  },
  (table) => [
    index("gallery_storage_deletions_due_idx").on(
      table.delete_after,
      table.storage_key,
    ),
    check(
      "gallery_storage_deletions_attempts_check",
      sql`${table.attempts} >= 0`,
    ),
  ],
);

// ── gallery_tags ───────────────────────────────────────────────
// Discord forum/media tags are retained after removal from a channel so old
// gallery posts keep their labels and emoji. `available` controls filter UI.
export const galleryTags = pgTable(
  "gallery_tags",
  {
    discord_tag_id: text("discord_tag_id").primaryKey(),
    channel_id: text("channel_id").notNull(),
    name: text("name").notNull(),
    emoji_id: text("emoji_id"),
    emoji_name: text("emoji_name"),
    moderated: boolean("moderated").notNull().default(false),
    available: boolean("available").notNull().default(true),
    position: integer("position").notNull(),
    last_synced_at: integer("last_synced_at").notNull(),
  },
  (table) => [
    index("gallery_tags_channel_available_idx").on(
      table.channel_id,
      table.available,
      table.position,
    ),
    check("gallery_tags_position_check", sql`${table.position} >= 0`),
  ],
);

// ── gallery_post_tags ─────────────────────────────────────────────
export const galleryPostTags = pgTable(
  "gallery_post_tags",
  {
    post_id: text("post_id")
      .notNull()
      .references(() => galleryPosts.thread_id, { onDelete: "cascade" }),
    tag_id: text("tag_id")
      .notNull()
      .references(() => galleryTags.discord_tag_id, { onDelete: "cascade" }),
  },
  (table) => [
    primaryKey({ columns: [table.post_id, table.tag_id] }),
    index("gallery_post_tags_tag_idx").on(table.tag_id, table.post_id),
  ],
);
