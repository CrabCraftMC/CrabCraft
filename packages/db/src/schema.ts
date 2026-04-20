import {
  pgTable,
  text,
  integer,
  boolean,
  serial,
  real,
  uniqueIndex,
  index,
} from "drizzle-orm/pg-core";

// ── users ───────────────────────────────────────────────────────
export const users = pgTable("users", {
  discord_id: text("discord_id").primaryKey(),
  discord_username: text("discord_username").notNull(),
  minecraft_username: text("minecraft_username"),
  minecraft_uuid: text("minecraft_uuid").unique(),
  active: boolean("active").notNull().default(false),
  created_at: integer("created_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
  updated_at: integer("updated_at")
    .notNull()
    .$defaultFn(() => Math.floor(Date.now() / 1000)),
  last_login_at: integer("last_login_at"),
  is_admin: boolean("is_admin").notNull().default(false),
});

// ── applications ────────────────────────────────────────────────
export const applications = pgTable(
  "applications",
  {
    id: serial("id").primaryKey(),
    discord_id: text("discord_id")
      .notNull()
      .references(() => users.discord_id),
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
