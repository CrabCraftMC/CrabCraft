import { cache } from "react";
import * as webQueries from "@crabcraft/db/queries/web";

// Functions that benefit from React cache (used in Server Components)
export const getMinecraftUuid = cache(webQueries.getMinecraftUuid);
export const getPlayerRole = cache(webQueries.getPlayerRole);
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
  getAwardLeaderboard,
  getAwardsSummary,
  getCrownLeaderboard,
  getPlayerAwardHoldings,
  getPlayerAwardScores,
  getPlayerCrownScore,
  getAwardServers,
  getAwardDefinitions,
  getAwardDefinition,
  AWARD_AGGREGATE_SERVER_ID,
} from "@crabcraft/db/queries/web";

// Re-export types for convenience
export type {
  SeasonWithPlaytime,
  AwardLeaderboardEntry,
  AwardSummaryEntry,
  CrownLeaderboardEntry,
  PlayerAwardHolding,
  AwardDefinition,
} from "@crabcraft/db/queries/web";
