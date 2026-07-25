import { cache } from "react";
import * as webQueries from "@crabcraft/db/queries/web";

// Functions that benefit from React cache (used in Server Components)
export const getUserByIdentifier = cache(webQueries.getUserByIdentifier);
export const getAltOwner = cache(webQueries.getAltOwner);
export const getJoinedSeason = cache(webQueries.getJoinedSeason);
export const getPlayerProfile = cache(webQueries.getPlayerProfile);
export const getPlayerCurrentStreak = cache(webQueries.getPlayerCurrentStreak);

// Functions used without cache
export {
  getSeasons,
  getPlayerSeasons,
  getHomepageStats,
  getPlayerStats,
  getPlayerRank,
  getServerAverages,
  searchUsers,
  getUserApplications,
  getAdminUsers,
  getOverviewStats,
} from "@crabcraft/db/queries/web";
