import type { PlayerSeasonStats } from "@/lib/types";

export function getFunFactsJoke(s: PlayerSeasonStats): string {
  if (s.fish_caught > 1000) return "The cod know you by name. They are nervous.";
  if (s.villagers_traded > 500) return "Emerald-fingered. The economy bends to your whims.";
  if (s.enchantments > 100) return "Anvil maintenance is your love language.";
  if (s.times_slept > 200) return "Sleep is technically server-side. You found a loophole.";
  if (s.jumps > 50000) return "Knees? Never met them.";
  if (s.animals_bred > 200) return "Beloved patron of livestock everywhere.";
  if (s.player_kills > 0) return "PvP enjoyer detected. Hide your wheat.";
  return "Quiet pursuits and steady hands. The crab way.";
}
