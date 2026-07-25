export function getMiningJoke(blocks: number): string {
  if (blocks <= 0) return "You looked at blocks. That counts, right?";
  if (blocks < 100) return "A few blocks. Maybe a quiet evening's gardening.";
  if (blocks < 1000) return "Respectable. A whole house's worth of stone.";
  if (blocks < 10000) return "You've dug deep. The crabs in the walls have noticed.";
  if (blocks < 50000) return "A pickaxe in every closet, just in case.";
  if (blocks < 100000) return "We can hear you mining from the surface.";
  if (blocks < 250000) return "Honestly at this point you ARE the cave.";
  if (blocks < 500000) return "Bedrock checks its calendar nervously when you log on.";
  return "Half a million blocks. We've contacted the Geological Society.";
}
