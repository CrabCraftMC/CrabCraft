export const CATEGORIES: { label: string; match: (key: string) => boolean }[] = [
  {
    label: "Combat",
    match: (k) =>
      k.startsWith("kill_") ||
      k.startsWith("killed_by_") ||
      ["damage_dealt", "damage_taken", "damage_shield", "use_totem", "win_raid", "trigger_raid"].includes(k),
  },
  { label: "Mining", match: (k) => k.startsWith("mine_") },
  { label: "Building", match: (k) => k.startsWith("place_") },
  { label: "Crafting", match: (k) => k.startsWith("craft_") },
  {
    label: "Food",
    match: (k) =>
      k.startsWith("eat_") ||
      k.startsWith("drink_") ||
      k.startsWith("harvest_") ||
      k.startsWith("collect_"),
  },
  {
    label: "Movement",
    match: (k) =>
      [
        "walk", "sprint", "swim", "aviate", "climb", "fall", "crouch",
        "jump", "dive", "walk_on_water",
      ].includes(k) || k.startsWith("ride_"),
  },
  {
    label: "Interaction",
    match: (k) =>
      k.startsWith("interact_") ||
      k.startsWith("use_") ||
      [
        "open_container", "trade", "enchant", "breed", "sleep",
        "ring_bell", "pot_flower", "noteblock", "play_record",
      ].includes(k),
  },
];

export function categorise<T extends { key: string }>(
  items: T[]
): Record<string, T[]> {
  const buckets: Record<string, T[]> = {};
  for (const cat of CATEGORIES) buckets[cat.label] = [];
  buckets["Other"] = [];

  for (const item of items) {
    let placed = false;
    for (const cat of CATEGORIES) {
      if (cat.match(item.key)) {
        buckets[cat.label].push(item);
        placed = true;
        break;
      }
    }
    if (!placed) buckets["Other"].push(item);
  }

  for (const label of Object.keys(buckets)) {
    if (buckets[label].length === 0) delete buckets[label];
  }

  return buckets;
}

export function fallbackTitle(key: string): string {
  const prefixes = [
    "kill_", "killed_by_", "mine_", "place_", "craft_", "eat_",
    "drink_", "harvest_", "collect_", "interact_", "use_", "ride_",
  ];
  let clean = key;
  for (const p of prefixes) {
    if (clean.startsWith(p)) {
      clean = clean.slice(p.length);
      break;
    }
  }
  return clean
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

type Localization = Record<string, string> | null;

export function getTitle(key: string, loc: Localization): string {
  return loc?.[`award.${key}.title`] || fallbackTitle(key);
}

export function getDesc(key: string, loc: Localization): string | null {
  return loc?.[`award.${key}.desc`] || null;
}
