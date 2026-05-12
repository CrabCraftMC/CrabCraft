export function getCombatJoke(kills: number, deaths: number): string {
  if (kills === 0 && deaths === 0) return "Pacifist run. Respect.";
  if (deaths === 0 && kills > 0) return "Unkillable. Untouchable. Slightly suspicious.";
  if (kills === 0) return "You died but never struck back. Brave or confused.";
  const kd = kills / Math.max(1, deaths);
  if (kd > 20) return "The mobs filed a complaint with the Mob Council.";
  if (kd > 10) return "Hostile mobs avoid your render distance now.";
  if (kd > 5) return "Statistically unfair. Beautifully so.";
  if (kd > 2) return "More wins than losses. The crab tide turns in your favour.";
  if (kd > 1) return "Just on the right side of the ledger.";
  if (kd > 0.5) return "It's been a learning experience. Many learning experiences.";
  if (kd > 0.2) return "Have you tried hiding? Hiding works.";
  return "The cave is your final friend. We will tell stories.";
}
