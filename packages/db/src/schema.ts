import {
  pgTable,
  pgEnum,
  text,
  integer,
  boolean,
  serial,
  real,
  jsonb,
  uniqueIndex,
  index,
} from "drizzle-orm/pg-core";

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
    over_15: boolean("over_15").notNull(),
    voice_chat: boolean("voice_chat").notNull(),
    policy_agreed: boolean("policy_agreed").notNull().default(false),
    status: text("status").notNull().default("pending"),
    join_reason: text("join_reason"),
    favourite_wood: text("favourite_wood"),
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
// each player's stats/<uuid>.json.
export const awards = pgTable("awards", {
  id: text("id").primaryKey(),
  title: text("title").notNull(),
  description: text("description").notNull().default(""),
  unit: text("unit").notNull(), // int | ticks | cm | tenths_of_heart
  bucket: text("bucket").notNull(), // combat | mining | crafting | building | items | food | movement | misc
  icon: text("icon").notNull(),
  // Reader spec used by the plugin to extract a value from
  // stats/<uuid>.json. Schema mirrors MinecraftStats' JSON readers.
  reader_type: text("reader_type").notNull(), // int | match-sum
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

// ── player_login_streaks ───────────────────────────────────────
// All-time login streaks per Minecraft account. Updated by the
// Velocity proxy on every join: the streak increments when the
// gap since the previous login is between the "same session"
// floor (12h) and the configurable buffer ceiling (default 36h).
// Outside that window it resets to 1.
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

// ── application_threads ─────────────────────────────────────────
// Private threads opened for each new member's whitelist application.
// Replaces the old "one text channel per applicant under a category"
// model: every applicant gets a private thread under the configured
// application channel. Threads have no `topic` to stash metadata in, so
// this table is the source of truth for thread↔applicant identity, the
// "you haven't applied yet" reminder flag, and the post-decision
// deletion window. Lifecycle: created on join → (optional) reminder →
// delete_after set on accept/deny → row + thread removed by cleanup.
export const applicationThreads = pgTable(
  "application_threads",
  {
    thread_id: text("thread_id").primaryKey(),
    applicant_id: text("applicant_id").notNull(),
    applicant_username: text("applicant_username").notNull(),
    guild_id: text("guild_id").notNull(),
    parent_channel_id: text("parent_channel_id").notNull(),
    reminded: boolean("reminded").notNull().default(false),
    // Unix seconds; set when the application is accepted/denied. The
    // thread (and the applicant's parent-channel access) is removed at
    // this point by the periodic + startup cleanup scans.
    delete_after: integer("delete_after"),
    created_at: integer("created_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
    updated_at: integer("updated_at")
      .notNull()
      .$defaultFn(() => Math.floor(Date.now() / 1000)),
  },
  (table) => [
    index("appthreads_applicant_idx").on(table.applicant_id),
    index("appthreads_delete_after_idx").on(table.delete_after),
  ],
);
