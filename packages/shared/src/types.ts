export interface MinecraftStatsFile {
  DataVersion: number;
  stats: {
    "minecraft:mined"?: Record<string, number>;
    "minecraft:crafted"?: Record<string, number>;
    "minecraft:used"?: Record<string, number>;
    "minecraft:killed"?: Record<string, number>;
    "minecraft:killed_by"?: Record<string, number>;
    "minecraft:picked_up"?: Record<string, number>;
    "minecraft:dropped"?: Record<string, number>;
    "minecraft:broken"?: Record<string, number>;
    "minecraft:custom"?: Record<string, number>;
  };
}

export interface PlayerSeasonStats {
  id: number;
  minecraft_uuid: string;
  season: string;
  play_time_seconds: number;
  walk_distance_m: number;
  sprint_distance_m: number;
  swim_distance_m: number;
  fly_distance_m: number;
  boat_distance_m: number;
  elytra_distance_m: number;
  horse_distance_m: number;
  climb_distance_m: number;
  fall_distance_m: number;
  total_distance_m: number;
  mob_kills: number;
  player_kills: number;
  deaths: number;
  damage_dealt: number;
  damage_taken: number;
  total_blocks_mined: number;
  total_blocks_placed: number;
  total_items_crafted: number;
  total_items_broken: number;
  jumps: number;
  animals_bred: number;
  fish_caught: number;
  villagers_traded: number;
  enchantments: number;
  times_slept: number;
  top_block_mined: TopItem | null;
  top_mob_killed: TopItem | null;
  top_item_crafted: TopItem | null;
  top_item_used: TopItem | null;
  top_death_cause: TopItem | null;
}

export interface TopItem {
  id: string;
  count: number;
}

export interface Season {
  id: string;
  name: string;
  start_date: string | null;
  end_date: string | null;
  is_current: boolean;
}

export interface LeaderboardEntry {
  minecraft_uuid: string;
  minecraft_username: string;
  value: number;
  rank: number;
}

export interface AdminUser {
  discord_id: string;
  discord_username: string;
  minecraft_username: string | null;
  minecraft_uuid: string | null;
  role: string;
  last_login_at: number | null;
  created_at: number;
  joined_season: string | null;
}

export interface Application {
  discord_id: string;
  discord_username: string;
  minecraft_username: string;
  minecraft_uuid: string;
  age_met: boolean;
  voice_chat: boolean;
  policy_agreed: boolean;
  status: "pending" | "accepted" | "denied";
  join_reason: string;
  favourite_wood: string;
  denial_reason: string | null;
  season: string;
  applied_at: number;
  resolved_at: number | null;
  resolved_by_discord_id: string | null;
}

export type LeaderboardCategory =
  | "play_time_seconds"
  | "total_distance_m"
  | "total_blocks_mined"
  | "mob_kills"
  | "deaths"
  | "total_items_crafted"
  | "fish_caught"
  | "animals_bred"
  | "jumps"
  | "damage_dealt";
