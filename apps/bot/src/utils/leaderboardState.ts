import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import logger from "./logger.js";

const STATE_FILE = "leaderboard_state.json";
const STATE_PATH = join(process.cwd(), STATE_FILE);

export interface LeaderboardState {
  channelId: string | null;
  messageId: string | null;
}

export async function saveLeaderboardState(state: LeaderboardState): Promise<void> {
  try {
    await writeFile(STATE_PATH, JSON.stringify(state, null, 2), "utf-8");
  } catch (error) {
    logger.error("Failed to save leaderboard state:", error);
  }
}

export async function loadLeaderboardState(): Promise<LeaderboardState> {
  try {
    const data = await readFile(STATE_PATH, "utf-8");
    return JSON.parse(data);
  } catch (error) {
    // If file doesn't exist or error, return default empty state
    return { channelId: null, messageId: null };
  }
}
