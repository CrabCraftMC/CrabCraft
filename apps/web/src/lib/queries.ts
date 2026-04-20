import { cache } from "react";
import * as webQueries from "@crabcraft/db/queries/web";

// Functions that benefit from React cache (used in Server Components)
export const getMinecraftUuid = cache(webQueries.getMinecraftUuid);
export const isPlayerAdmin = cache(webQueries.isPlayerAdmin);
export const getMinecraftUsername = cache(webQueries.getMinecraftUsername);
export const getUserByIdentifier = cache(webQueries.getUserByIdentifier);
export const getJoinedSeason = cache(webQueries.getJoinedSeason);
export const getPlayerProfile = cache(webQueries.getPlayerProfile);

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
} from "@crabcraft/db/queries/web";

// Re-export types for convenience
export type { SeasonWithPlaytime } from "@crabcraft/db/queries/web";
