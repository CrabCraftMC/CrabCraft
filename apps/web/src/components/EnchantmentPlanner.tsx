"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import {
  AlertTriangle,
  BookOpen,
  Check,
  Copy,
  Minus,
  Plus,
  RotateCcw,
  Sparkles,
  WandSparkles,
} from "lucide-react";

const STORAGE_KEY = "crabcraft-enchantment-planner";
const BOOK_ICON = "/minecraft/item/enchanted_book.png";

type Enchantment = {
  id: string;
  name: string;
  maxLevel: number;
  description: string;
  conflicts?: string[];
  treasure?: boolean;
  curse?: boolean;
};

type ItemPreset = {
  key: string;
  name: string;
  icon: string;
  enchantments: string[];
  recommended: string[];
};

const DAMAGE_CONFLICTS = ["sharpness", "smite", "bane_of_arthropods", "density", "breach"];
const PROTECTION_CONFLICTS = [
  "protection",
  "fire_protection",
  "blast_protection",
  "projectile_protection",
];

const ENCHANTMENTS: Record<string, Enchantment> = {
  sharpness: {
    id: "sharpness",
    name: "Sharpness",
    maxLevel: 5,
    description: "Increases melee damage against most targets.",
    conflicts: DAMAGE_CONFLICTS,
  },
  smite: {
    id: "smite",
    name: "Smite",
    maxLevel: 5,
    description: "Extra melee damage against undead mobs.",
    conflicts: DAMAGE_CONFLICTS,
  },
  bane_of_arthropods: {
    id: "bane_of_arthropods",
    name: "Bane of Arthropods",
    maxLevel: 5,
    description: "Extra damage and slowness against arthropods.",
    conflicts: DAMAGE_CONFLICTS,
  },
  sweeping_edge: {
    id: "sweeping_edge",
    name: "Sweeping Edge",
    maxLevel: 3,
    description: "Improves sword sweep attack damage.",
  },
  looting: {
    id: "looting",
    name: "Looting",
    maxLevel: 3,
    description: "Increases mob drop rolls.",
  },
  fire_aspect: {
    id: "fire_aspect",
    name: "Fire Aspect",
    maxLevel: 2,
    description: "Sets melee targets on fire.",
  },
  knockback: {
    id: "knockback",
    name: "Knockback",
    maxLevel: 2,
    description: "Pushes hit mobs further away.",
  },
  efficiency: {
    id: "efficiency",
    name: "Efficiency",
    maxLevel: 5,
    description: "Speeds up block breaking.",
  },
  fortune: {
    id: "fortune",
    name: "Fortune",
    maxLevel: 3,
    description: "Increases drops from many blocks.",
    conflicts: ["silk_touch"],
  },
  silk_touch: {
    id: "silk_touch",
    name: "Silk Touch",
    maxLevel: 1,
    description: "Drops blocks in their original form.",
    conflicts: ["fortune"],
  },
  power: {
    id: "power",
    name: "Power",
    maxLevel: 5,
    description: "Increases bow arrow damage.",
  },
  punch: {
    id: "punch",
    name: "Punch",
    maxLevel: 2,
    description: "Adds bow knockback.",
  },
  flame: {
    id: "flame",
    name: "Flame",
    maxLevel: 1,
    description: "Fires burning arrows.",
  },
  infinity: {
    id: "infinity",
    name: "Infinity",
    maxLevel: 1,
    description: "Consumes no regular arrows while one is carried.",
    conflicts: ["mending"],
  },
  quick_charge: {
    id: "quick_charge",
    name: "Quick Charge",
    maxLevel: 3,
    description: "Reduces crossbow reload time.",
  },
  multishot: {
    id: "multishot",
    name: "Multishot",
    maxLevel: 1,
    description: "Fires three projectiles at once.",
    conflicts: ["piercing"],
  },
  piercing: {
    id: "piercing",
    name: "Piercing",
    maxLevel: 4,
    description: "Projectiles pass through entities.",
    conflicts: ["multishot"],
  },
  loyalty: {
    id: "loyalty",
    name: "Loyalty",
    maxLevel: 3,
    description: "Thrown tridents return to you.",
    conflicts: ["riptide"],
  },
  riptide: {
    id: "riptide",
    name: "Riptide",
    maxLevel: 3,
    description: "Launches you with a trident in water or rain.",
    conflicts: ["loyalty", "channeling"],
  },
  channeling: {
    id: "channeling",
    name: "Channeling",
    maxLevel: 1,
    description: "Summons lightning during thunderstorms.",
    conflicts: ["riptide"],
  },
  impaling: {
    id: "impaling",
    name: "Impaling",
    maxLevel: 5,
    description: "Increases trident damage against aquatic mobs.",
  },
  density: {
    id: "density",
    name: "Density",
    maxLevel: 5,
    description: "Increases mace smash damage from falling.",
    conflicts: DAMAGE_CONFLICTS,
  },
  breach: {
    id: "breach",
    name: "Breach",
    maxLevel: 4,
    description: "Reduces armor effectiveness against mace hits.",
    conflicts: DAMAGE_CONFLICTS,
  },
  wind_burst: {
    id: "wind_burst",
    name: "Wind Burst",
    maxLevel: 3,
    description: "Launches you upward after a mace smash attack.",
    treasure: true,
  },
  lunge: {
    id: "lunge",
    name: "Lunge",
    maxLevel: 3,
    description: "Surges you forward after a spear attack.",
  },
  protection: {
    id: "protection",
    name: "Protection",
    maxLevel: 4,
    description: "Reduces most incoming damage.",
    conflicts: PROTECTION_CONFLICTS,
  },
  fire_protection: {
    id: "fire_protection",
    name: "Fire Protection",
    maxLevel: 4,
    description: "Reduces fire and lava damage.",
    conflicts: PROTECTION_CONFLICTS,
  },
  blast_protection: {
    id: "blast_protection",
    name: "Blast Protection",
    maxLevel: 4,
    description: "Reduces explosion damage and knockback.",
    conflicts: PROTECTION_CONFLICTS,
  },
  projectile_protection: {
    id: "projectile_protection",
    name: "Projectile Protection",
    maxLevel: 4,
    description: "Reduces arrow and projectile damage.",
    conflicts: PROTECTION_CONFLICTS,
  },
  thorns: {
    id: "thorns",
    name: "Thorns",
    maxLevel: 3,
    description: "Sometimes damages attackers.",
  },
  respiration: {
    id: "respiration",
    name: "Respiration",
    maxLevel: 3,
    description: "Extends underwater breathing time.",
  },
  aqua_affinity: {
    id: "aqua_affinity",
    name: "Aqua Affinity",
    maxLevel: 1,
    description: "Removes underwater mining slowdown.",
  },
  feather_falling: {
    id: "feather_falling",
    name: "Feather Falling",
    maxLevel: 4,
    description: "Reduces fall damage.",
  },
  depth_strider: {
    id: "depth_strider",
    name: "Depth Strider",
    maxLevel: 3,
    description: "Increases underwater movement speed.",
    conflicts: ["frost_walker"],
  },
  frost_walker: {
    id: "frost_walker",
    name: "Frost Walker",
    maxLevel: 2,
    description: "Freezes water beneath your feet.",
    conflicts: ["depth_strider"],
    treasure: true,
  },
  soul_speed: {
    id: "soul_speed",
    name: "Soul Speed",
    maxLevel: 3,
    description: "Increases speed on soul sand and soul soil.",
    treasure: true,
  },
  swift_sneak: {
    id: "swift_sneak",
    name: "Swift Sneak",
    maxLevel: 3,
    description: "Increases movement speed while sneaking.",
    treasure: true,
  },
  unbreaking: {
    id: "unbreaking",
    name: "Unbreaking",
    maxLevel: 3,
    description: "Improves item durability.",
  },
  mending: {
    id: "mending",
    name: "Mending",
    maxLevel: 1,
    description: "Uses XP to repair durability.",
    conflicts: ["infinity"],
    treasure: true,
  },
  curse_of_vanishing: {
    id: "curse_of_vanishing",
    name: "Curse of Vanishing",
    maxLevel: 1,
    description: "Item disappears when you die.",
    curse: true,
  },
  curse_of_binding: {
    id: "curse_of_binding",
    name: "Curse of Binding",
    maxLevel: 1,
    description: "Armor cannot be removed normally.",
    curse: true,
  },
  luck_of_the_sea: {
    id: "luck_of_the_sea",
    name: "Luck of the Sea",
    maxLevel: 3,
    description: "Improves fishing treasure odds.",
  },
  lure: {
    id: "lure",
    name: "Lure",
    maxLevel: 3,
    description: "Shortens the wait between fish bites.",
  },
};

const ITEMS: ItemPreset[] = [
  {
    key: "sword",
    name: "Sword",
    icon: "/minecraft/item/diamond_sword.png",
    enchantments: [
      "sharpness",
      "smite",
      "bane_of_arthropods",
      "sweeping_edge",
      "looting",
      "fire_aspect",
      "knockback",
      "unbreaking",
      "mending",
      "curse_of_vanishing",
    ],
    recommended: ["sharpness", "sweeping_edge", "looting", "unbreaking", "mending"],
  },
  {
    key: "axe",
    name: "Axe",
    icon: "/minecraft/item/diamond_axe.png",
    enchantments: [
      "sharpness",
      "smite",
      "bane_of_arthropods",
      "efficiency",
      "fortune",
      "silk_touch",
      "unbreaking",
      "mending",
      "curse_of_vanishing",
    ],
    recommended: ["efficiency", "sharpness", "fortune", "unbreaking", "mending"],
  },
  {
    key: "pickaxe",
    name: "Pickaxe",
    icon: "/minecraft/item/diamond_pickaxe.png",
    enchantments: ["efficiency", "fortune", "silk_touch", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["efficiency", "fortune", "unbreaking", "mending"],
  },
  {
    key: "shovel",
    name: "Shovel",
    icon: "/minecraft/item/diamond_shovel.png",
    enchantments: ["efficiency", "fortune", "silk_touch", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["efficiency", "silk_touch", "unbreaking", "mending"],
  },
  {
    key: "hoe",
    name: "Hoe",
    icon: "/minecraft/item/diamond_hoe.png",
    enchantments: ["efficiency", "fortune", "silk_touch", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["efficiency", "fortune", "unbreaking", "mending"],
  },
  {
    key: "bow",
    name: "Bow",
    icon: "/minecraft/item/bow.png",
    enchantments: ["power", "punch", "flame", "infinity", "mending", "unbreaking", "curse_of_vanishing"],
    recommended: ["power", "flame", "infinity", "unbreaking"],
  },
  {
    key: "crossbow",
    name: "Crossbow",
    icon: "/minecraft/item/crossbow.png",
    enchantments: ["quick_charge", "multishot", "piercing", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["quick_charge", "multishot", "unbreaking", "mending"],
  },
  {
    key: "trident",
    name: "Trident",
    icon: "/minecraft/item/trident.png",
    enchantments: ["loyalty", "riptide", "channeling", "impaling", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["loyalty", "channeling", "impaling", "unbreaking", "mending"],
  },
  {
    key: "mace",
    name: "Mace",
    icon: "/minecraft/item/mace.png",
    enchantments: ["density", "breach", "smite", "bane_of_arthropods", "wind_burst", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["density", "wind_burst", "unbreaking", "mending"],
  },
  {
    key: "spear",
    name: "Spear",
    icon: "/minecraft/item/diamond_spear.png",
    enchantments: [
      "sharpness",
      "smite",
      "bane_of_arthropods",
      "looting",
      "fire_aspect",
      "knockback",
      "lunge",
      "unbreaking",
      "mending",
      "curse_of_vanishing",
    ],
    recommended: ["sharpness", "looting", "fire_aspect", "lunge", "unbreaking", "mending"],
  },
  {
    key: "helmet",
    name: "Helmet",
    icon: "/minecraft/item/diamond_helmet.png",
    enchantments: [
      "protection",
      "fire_protection",
      "blast_protection",
      "projectile_protection",
      "respiration",
      "aqua_affinity",
      "thorns",
      "unbreaking",
      "mending",
      "curse_of_binding",
      "curse_of_vanishing",
    ],
    recommended: ["protection", "respiration", "aqua_affinity", "unbreaking", "mending"],
  },
  {
    key: "chestplate",
    name: "Chestplate",
    icon: "/minecraft/item/diamond_chestplate.png",
    enchantments: [
      "protection",
      "fire_protection",
      "blast_protection",
      "projectile_protection",
      "thorns",
      "unbreaking",
      "mending",
      "curse_of_binding",
      "curse_of_vanishing",
    ],
    recommended: ["protection", "unbreaking", "mending"],
  },
  {
    key: "leggings",
    name: "Leggings",
    icon: "/minecraft/item/diamond_leggings.png",
    enchantments: [
      "protection",
      "fire_protection",
      "blast_protection",
      "projectile_protection",
      "swift_sneak",
      "thorns",
      "unbreaking",
      "mending",
      "curse_of_binding",
      "curse_of_vanishing",
    ],
    recommended: ["protection", "swift_sneak", "unbreaking", "mending"],
  },
  {
    key: "boots",
    name: "Boots",
    icon: "/minecraft/item/diamond_boots.png",
    enchantments: [
      "protection",
      "fire_protection",
      "blast_protection",
      "projectile_protection",
      "feather_falling",
      "depth_strider",
      "frost_walker",
      "soul_speed",
      "thorns",
      "unbreaking",
      "mending",
      "curse_of_binding",
      "curse_of_vanishing",
    ],
    recommended: ["protection", "feather_falling", "depth_strider", "soul_speed", "unbreaking", "mending"],
  },
  {
    key: "elytra",
    name: "Elytra",
    icon: "/minecraft/item/elytra.png",
    enchantments: ["unbreaking", "mending", "curse_of_binding", "curse_of_vanishing"],
    recommended: ["unbreaking", "mending"],
  },
  {
    key: "fishing_rod",
    name: "Fishing Rod",
    icon: "/minecraft/item/fishing_rod.png",
    enchantments: ["luck_of_the_sea", "lure", "unbreaking", "mending", "curse_of_vanishing"],
    recommended: ["luck_of_the_sea", "lure", "unbreaking", "mending"],
  },
];

function loadSaved() {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function toRoman(level: number) {
  return ["", "I", "II", "III", "IV", "V"][level] ?? String(level);
}

function levelLabel(enchantment: Enchantment, level: number) {
  return enchantment.maxLevel === 1 ? "Max" : toRoman(level);
}

function conflictsWith(a: string, b: string) {
  if (a === b) return false;
  return (
    ENCHANTMENTS[a]?.conflicts?.includes(b) ||
    ENCHANTMENTS[b]?.conflicts?.includes(a) ||
    false
  );
}

function selectedFrom(ids: string[]) {
  return Object.fromEntries(
    ids.map((id) => [id, ENCHANTMENTS[id].maxLevel])
  ) as Record<string, number>;
}

function commandFor(id: string, level: number) {
  return `/enchant @s minecraft:${id} ${level}`;
}

export default function EnchantmentPlanner() {
  const [itemKey, setItemKey] = useState(ITEMS[0].key);
  const [selected, setSelected] = useState<Record<string, number>>({});
  const [hydrated, setHydrated] = useState(false);
  const [copiedCommand, setCopiedCommand] = useState<string | null>(null);

  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      if (ITEMS.some((item) => item.key === saved.itemKey)) {
        setItemKey(saved.itemKey);
      }
      if (saved.selected && typeof saved.selected === "object") {
        setSelected(
          Object.fromEntries(
            Object.entries(saved.selected).filter(
              ([id, level]) =>
                ENCHANTMENTS[id] && typeof level === "number"
            )
          ) as Record<string, number>
        );
      }
    }
    setHydrated(true);
  }, []);

  const item = useMemo(
    () => ITEMS.find((entry) => entry.key === itemKey) ?? ITEMS[0],
    [itemKey]
  );

  useEffect(() => {
    setSelected((prev) => {
      const allowed = new Set(item.enchantments);
      const next = Object.fromEntries(
        Object.entries(prev).filter(([id]) => allowed.has(id))
      ) as Record<string, number>;
      return Object.keys(next).length === Object.keys(prev).length ? prev : next;
    });
  }, [item]);

  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ itemKey, selected }));
  }, [itemKey, selected, hydrated]);

  const selectedIds = Object.keys(selected);
  const selectedEntries = selectedIds.map((id) => ({
    enchantment: ENCHANTMENTS[id],
    level: selected[id],
  }));

  const toggleEnchantment = useCallback((id: string) => {
    setSelected((prev) => {
      const next = { ...prev };
      if (next[id]) {
        delete next[id];
        return next;
      }
      for (const selectedId of Object.keys(next)) {
        if (conflictsWith(id, selectedId)) delete next[selectedId];
      }
      next[id] = ENCHANTMENTS[id].maxLevel;
      return next;
    });
  }, []);

  const setLevel = useCallback((id: string, level: number) => {
    setSelected((prev) => ({
      ...prev,
      [id]: Math.max(1, Math.min(ENCHANTMENTS[id].maxLevel, level)),
    }));
  }, []);

  const chooseItem = useCallback((key: string) => {
    setItemKey(key);
    setSelected({});
  }, []);

  const applyRecommended = useCallback(() => {
    setSelected(selectedFrom(item.recommended));
  }, [item]);

  const clearAll = useCallback(() => {
    setSelected({});
  }, []);

  const commands = selectedEntries.map(({ enchantment, level }) =>
    commandFor(enchantment.id, level)
  );

  const copyCommand = useCallback((command: string) => {
    navigator.clipboard.writeText(command);
    setCopiedCommand(command);
    setTimeout(() => {
      setCopiedCommand((current) => (current === command ? null : current));
    }, 1500);
  }, []);

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-7xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Enchantment Planner
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Build compatible Minecraft enchantment loadouts for your gear
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-[300px_1fr] gap-6 items-start">
          <Squircle
            cornerRadius={32}
            className="p-5 bg-paper-2 shadow-sm animate-in"
            style={{ animationDelay: "0.1s" }}
          >
            <div className="mb-4">
              <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                Item
              </h2>
            </div>

            <div className="grid grid-cols-2 gap-2">
              {ITEMS.map((entry) => (
                <button
                  key={entry.key}
                  onClick={() => chooseItem(entry.key)}
                  className={`min-h-[74px] rounded-2xl p-3 text-left cursor-pointer transition-colors ${
                    item.key === entry.key
                      ? "bg-orange-500 text-white"
                      : "bg-paper hover:bg-gray-100 dark:hover:bg-white/10 text-gray-700 dark:text-gray-300"
                  }`}
                >
                  <PixelIcon
                    src={entry.icon}
                    size={32}
                    className="mb-2"
                  />
                  <span className="block text-xs font-bold truncate">
                    {entry.name}
                  </span>
                </button>
              ))}
            </div>
          </Squircle>

          <div className="space-y-6">
            <Squircle
              cornerRadius={32}
              className="p-6 bg-paper-2 shadow-sm animate-in"
              style={{ animationDelay: "0.15s" }}
            >
              <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div className="flex items-center gap-4 min-w-0">
                  <div className="w-14 h-14 rounded-2xl bg-paper flex items-center justify-center shrink-0">
                    <PixelIcon
                      src={item.icon}
                      size={40}
                    />
                  </div>
                  <div className="min-w-0">
                    <span className="text-sm font-bold text-gray-500 dark:text-gray-400">
                      Current Loadout
                    </span>
                    <p className="text-2xl lg:text-3xl font-bold text-gray-800 dark:text-gray-100 truncate">
                      {item.name}
                    </p>
                  </div>
                </div>

                <div className="flex flex-wrap gap-2">
                  <button
                    onClick={applyRecommended}
                    className="inline-flex items-center gap-2 rounded-xl bg-orange-500 hover:bg-orange-600 text-white font-bold px-4 py-2.5 text-sm transition-colors cursor-pointer active:scale-95"
                  >
                    <WandSparkles className="w-4 h-4" />
                    Best Set
                  </button>
                  <button
                    onClick={clearAll}
                    className="inline-flex items-center gap-2 rounded-xl bg-paper hover:bg-line text-gray-700 dark:text-gray-300 font-bold px-4 py-2.5 text-sm transition-colors cursor-pointer active:scale-95"
                  >
                    <RotateCcw className="w-4 h-4" />
                    Clear
                  </button>
                </div>
              </div>

              <div className="mt-5 flex flex-wrap gap-2">
                {selectedEntries.length === 0 ? (
                  <span className="text-sm text-gray-500 dark:text-gray-400">
                    Pick enchantments below or start with the recommended set.
                  </span>
                ) : (
                  selectedEntries.map(({ enchantment, level }) => (
                    <button
                      key={enchantment.id}
                      onClick={() => toggleEnchantment(enchantment.id)}
                      className={`group inline-flex items-center gap-2 rounded-xl px-3 py-2 text-xs font-bold cursor-pointer transition-colors ${
                        enchantment.curse
                          ? "bg-red-500/10 text-red-500 hover:bg-red-500/20"
                          : "bg-orange-500/10 text-orange-500 hover:bg-orange-500/20"
                      }`}
                    >
                      <PixelIcon
                        src={BOOK_ICON}
                        size={24}
                      />
                      <span className="group-hover:line-through">
                        {enchantment.name} {levelLabel(enchantment, level)}
                      </span>
                    </button>
                  ))
                )}
              </div>
            </Squircle>

            <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_380px] gap-6 items-start">
              <Squircle
                cornerRadius={32}
                className="p-6 bg-paper-2 shadow-sm animate-in"
                style={{ animationDelay: "0.2s" }}
              >
                <div className="flex items-center gap-2 mb-4">
                  <div className="p-1.5 rounded-lg bg-orange-500/10">
                    <BookOpen className="w-4 h-4 text-orange-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Enchantments
                  </h2>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {item.enchantments.map((id) => {
                    const enchantment = ENCHANTMENTS[id];
                    const activeLevel = selected[id];
                    const replacing = selectedIds
                      .filter((selectedId) => conflictsWith(id, selectedId))
                      .map((selectedId) => ENCHANTMENTS[selectedId].name);

                    return (
                      <div
                        key={id}
                        role="button"
                        tabIndex={0}
                        aria-pressed={Boolean(activeLevel)}
                        onClick={() => toggleEnchantment(id)}
                        onKeyDown={(event) => {
                          if (event.key === "Enter" || event.key === " ") {
                            event.preventDefault();
                            toggleEnchantment(id);
                          }
                        }}
                        className={`rounded-2xl border p-4 cursor-pointer transition-colors ${
                          activeLevel
                            ? "bg-orange-500/10 border-orange-500/40"
                            : "bg-paper border-transparent hover:border-orange-500/30"
                        }`}
                      >
                        <div className="flex items-start gap-3">
                          <PixelIcon
                            src={BOOK_ICON}
                            size={32}
                          />
                          <div className="min-w-0 flex-1">
                            <div className="flex items-start justify-between gap-2">
                              <h3 className="text-sm font-bold text-gray-800 dark:text-gray-100">
                                {enchantment.name}
                              </h3>
                              <span className="shrink-0 text-xs font-bold text-gray-400">
                                {enchantment.maxLevel === 1
                                  ? "Max"
                                  : `I-${toRoman(enchantment.maxLevel)}`}
                              </span>
                            </div>
                            <p className="mt-1 text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                              {enchantment.description}
                            </p>
                          </div>
                        </div>

                        <div className="mt-3 flex flex-wrap gap-1.5">
                          {enchantment.treasure && (
                            <span className="rounded-lg bg-blue-500/10 px-2 py-1 text-[10px] font-bold text-blue-500">
                              Treasure
                            </span>
                          )}
                          {enchantment.curse && (
                            <span className="rounded-lg bg-red-500/10 px-2 py-1 text-[10px] font-bold text-red-500">
                              Curse
                            </span>
                          )}
                          {!activeLevel && replacing.length > 0 && (
                            <span className="rounded-lg bg-amber-500/10 px-2 py-1 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                              Replaces {replacing.join(", ")}
                            </span>
                          )}
                        </div>

                        {activeLevel && enchantment.maxLevel > 1 && (
                          <div className="mt-4 flex items-center justify-between gap-3">
                            <button
                              onClick={(event) => {
                                event.stopPropagation();
                                setLevel(id, activeLevel - 1);
                              }}
                              className="w-9 h-9 rounded-xl bg-paper hover:bg-line flex items-center justify-center text-gray-500 cursor-pointer transition-colors"
                              aria-label={`Lower ${enchantment.name} level`}
                            >
                              <Minus className="w-4 h-4" />
                            </button>
                            <span className="text-sm font-bold text-gray-800 dark:text-gray-100">
                              Level {toRoman(activeLevel)}
                            </span>
                            <button
                              onClick={(event) => {
                                event.stopPropagation();
                                setLevel(id, activeLevel + 1);
                              }}
                              className="w-9 h-9 rounded-xl bg-paper hover:bg-line flex items-center justify-center text-gray-500 cursor-pointer transition-colors"
                              aria-label={`Raise ${enchantment.name} level`}
                            >
                              <Plus className="w-4 h-4" />
                            </button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </Squircle>

              <Squircle
                cornerRadius={32}
                className="p-6 bg-paper-2 shadow-sm animate-in"
                style={{ animationDelay: "0.25s" }}
              >
                <div className="flex items-center gap-2 mb-4">
                  <div className="p-1.5 rounded-lg bg-blue-500/10">
                    <Sparkles className="w-4 h-4 text-blue-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Result
                  </h2>
                </div>

                <div className="grid grid-cols-3 gap-2 mb-5">
                  <div className="rounded-2xl bg-paper p-3">
                    <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                      {selectedEntries.length}
                    </p>
                    <p className="text-[10px] font-bold text-gray-400">Chosen</p>
                  </div>
                  <div className="rounded-2xl bg-paper p-3">
                    <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                      {selectedEntries.filter(({ enchantment }) => enchantment.treasure).length}
                    </p>
                    <p className="text-[10px] font-bold text-gray-400">Treasure</p>
                  </div>
                  <div className="rounded-2xl bg-paper p-3">
                    <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                      {selectedEntries.filter(({ enchantment }) => enchantment.curse).length}
                    </p>
                    <p className="text-[10px] font-bold text-gray-400">Curses</p>
                  </div>
                </div>

                <div className="rounded-2xl bg-paper p-4">
                  <div className="flex items-center gap-2 mb-3">
                    <AlertTriangle className="w-4 h-4 text-green-500" />
                    <span className="text-sm font-bold text-gray-700 dark:text-gray-300">
                      Compatibility
                    </span>
                  </div>
                  <p className="text-sm leading-relaxed text-gray-500 dark:text-gray-400">
                    Incompatible picks are replaced automatically, so the shown
                    loadout stays legal for the selected item.
                  </p>
                </div>

                <div className="mt-5">
                  <div className="flex items-center justify-between gap-3 mb-2">
                    <span className="text-sm font-bold text-gray-700 dark:text-gray-300">
                      Commands
                    </span>
                  </div>

                  {commands.length === 0 ? (
                    <p className="text-sm text-gray-500 dark:text-gray-400">
                      Select enchantments to generate commands.
                    </p>
                  ) : (
                    <div className="space-y-2">
                      {commands.map((command) => (
                        <div
                          key={command}
                          className="flex items-start gap-2 rounded-xl bg-paper p-2"
                        >
                          <code className="min-w-0 flex-1 px-1 py-1 text-[11px] leading-relaxed text-gray-700 dark:text-gray-300 break-all">
                            {command}
                          </code>
                          <button
                            onClick={() => copyCommand(command)}
                            aria-label={`Copy command ${command}`}
                            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-orange-500 text-white transition-colors hover:bg-orange-600 cursor-pointer"
                          >
                            {copiedCommand === command ? (
                              <Check className="w-3.5 h-3.5" />
                            ) : (
                              <Copy className="w-3.5 h-3.5" />
                            )}
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </Squircle>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
