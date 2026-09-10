export function leaderboardApiUrl(path: string): string {
  const base = process.env.MINECRAFT_API_BASE_URL ?? "https://api.crabcraft.net";
  return `${base.replace(/\/$/, "")}${path}`;
}
