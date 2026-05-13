import type { PlayerSeasonStats } from "./types";

export interface WrappedData {
  stats: PlayerSeasonStats;
  averages: Record<string, number>;
  ranks: Record<string, number>;
  playerName: string;
  playerUuid: string;
  season: string;
  totalPlayers: number;
}

export type WrappedErrorKind = "no-mc" | "no-data" | "fetch-error";

export type WrappedDataResult =
  | { kind: "ok"; data: WrappedData }
  | { kind: WrappedErrorKind };
