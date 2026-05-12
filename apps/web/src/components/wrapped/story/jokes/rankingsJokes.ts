export function getRankingsJoke(top3Count: number, hasRank1: boolean): string {
  if (hasRank1 && top3Count >= 3) return "Three first-place flags? You've broken the leaderboard, friend.";
  if (hasRank1) return "Top of the pile. The crabs salute you.";
  if (top3Count >= 3) return "Podium in three categories. A renaissance crustacean.";
  if (top3Count > 0) return "Top three. Reasonable bragging rights granted.";
  return "Top of nothing, bottom of nothing. Cosmically balanced.";
}
