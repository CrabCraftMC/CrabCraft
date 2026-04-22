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
// Per-player, per-award, per-server score for a season.
// server_id = "__aggregate__" represents the sum across all servers.
export const AGGREGATE_SERVER_ID = "__aggregate__";

export const playerAwardScores = pgTable(
  "player_award_scores",
  {
    id: serial("id").primaryKey(),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    season: text("season").notNull(),
    server_id: text("server_id").notNull(),
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
    uniqueIndex("pas_uuid_season_server_award_unique").on(
      table.minecraft_uuid,
      table.season,
      table.server_id,
      table.award_id,
    ),
    index("pas_leaderboard_idx").on(
      table.season,
      table.server_id,
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
// server_id = "__aggregate__" represents the union across all servers.
export const playerAdvancements = pgTable(
  "player_advancements",
  {
    id: serial("id").primaryKey(),
    minecraft_uuid: text("minecraft_uuid").notNull(),
    season: text("season").notNull(),
    server_id: text("server_id").notNull(),
    advancement_id: text("advancement_id").notNull(),
    completed: boolean("completed").notNull().default(false),
    completed_at: integer("completed_at"),
  },
  (table) => [
    uniqueIndex("pa_uuid_season_server_adv_unique").on(
      table.minecraft_uuid,
      table.season,
      table.server_id,
      table.advancement_id,
    ),
    index("pa_leaderboard_idx").on(
      table.season,
      table.server_id,
      table.completed,
    ),
    index("pa_player_idx").on(table.minecraft_uuid, table.season),
  ],
);
