export function getBuildingJoke(crafted: number, placed: number): string {
  const t = crafted + placed;
  if (t <= 0) return "Nothing built. Nothing broken. A monument to stillness.";
  if (t < 500) return "A few projects. Foundations matter.";
  if (t < 5000) return "Your inventory has known many tools.";
  if (t < 25000) return "A real builder. Beware: scope creep nips at every claw.";
  if (t < 100000) return "Architectural ambition meets pickaxe stamina.";
  if (t < 500000) return "Half a million actions. The world has been reshaped in your image.";
  return "There is no biome you have not negotiated with personally.";
}
