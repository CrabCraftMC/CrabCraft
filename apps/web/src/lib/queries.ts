import { cache } from "react";
import * as webQueries from "@crabcraft/db/queries/web";

// Functions that benefit from React cache (used in Server Components)
export const getMinecraftUuid = cache(webQueries.getMinecraftUuid);
export const getPlayerRole = cache(webQueries.getPlayerRole);
export const getMinecraftUsername = cache(webQueries.getMinecraftUsername);
export const getUserByIdentifier = cache(webQueries.getUserByIdentifier);
export const getJoinedSeason = cache(webQueries.getJoinedSeason);
export const getPlayerProfile = cache(webQueries.getPlayerProfile);
export const getPlayerCurrentStreak = cache(webQueries.getPlayerCurrentStreak);

// Functions used without cache
export {
  getSeasons,
  getPlayerSeasons,
  getCurrentSeason,
  getPlayerStats,
  getLeaderboard,
  getPlayerRank,
  getServerAverages,
  searchUsers,
  getUserApplications,
  getAdminUsers,
  getOverviewStats,
} from "@crabcraft/db/queries/web";

// Re-export types for convenience
export type {
  SeasonWithPlaytime,
} from "@crabcraft/db/queries/web";
