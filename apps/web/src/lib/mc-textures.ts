/**
 * Returns an ordered list of candidate texture paths for a Minecraft ID.
 * Tries block first (most mined/placed stats are blocks), then item, then
 * entity. The McIdTexture component walks the list until one loads.
 */
export function mcIdToTextureCandidates(id: string): string[] {
  const clean = id.replace(/^minecraft:/, "");
  return [
    `block/${clean}.png`,
    `item/${clean}.png`,
    `entity/${clean}.png`,
  ];
}

export function formatMcId(id: string | null | undefined): string | null {
  return id ? id.replace(/^minecraft:/, "").replace(/_/g, " ") : null;
}
