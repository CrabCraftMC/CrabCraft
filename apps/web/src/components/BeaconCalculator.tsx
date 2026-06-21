"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import { Boxes, Package, RotateCcw, Sparkles } from "lucide-react";

const STORAGE_KEY = "crabcraft-beacon-calculator";

const DEFAULTS = {
  level: 4,
  material: "Iron",
};

const MATERIALS = [
  { name: "Iron", itemName: "Iron Ingots", texture: "/minecraft/item/iron_ingot.png" },
  { name: "Gold", itemName: "Gold Ingots", texture: "/minecraft/item/gold_ingot.png" },
  { name: "Emerald", itemName: "Emeralds", texture: "/minecraft/item/emerald.png" },
  { name: "Diamond", itemName: "Diamonds", texture: "/minecraft/item/diamond.png" },
  { name: "Netherite", itemName: "Netherite Ingots", texture: "/minecraft/item/netherite_ingot.png" },
];

const LEVELS = [
  { level: 1, layers: [3], blocks: 9, range: 20 },
  { level: 2, layers: [5, 3], blocks: 34, range: 30 },
  { level: 3, layers: [7, 5, 3], blocks: 83, range: 40 },
  { level: 4, layers: [9, 7, 5, 3], blocks: 164, range: 50 },
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

function formatAmount(value: number) {
  return new Intl.NumberFormat("en-US").format(value);
}

function compactStackText(items: number) {
  const stacks = Math.floor(items / 64);
  const loose = items % 64;
  if (stacks === 0) return `${loose}i`;
  if (loose === 0) return `${stacks}s`;
  return `${stacks}s ${loose}i`;
}

export default function BeaconCalculator() {
  const [level, setLevel] = useState(DEFAULTS.level);
  const [material, setMaterial] = useState(DEFAULTS.material);
  const [hydrated, setHydrated] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);

  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      if (LEVELS.some((entry) => entry.level === saved.level)) {
        setLevel(saved.level);
      }
      if (MATERIALS.some((entry) => entry.name === saved.material)) {
        setMaterial(saved.material);
      }
    }
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ level, material })
    );
  }, [level, material, hydrated]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && showResetConfirm) setShowResetConfirm(false);
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [showResetConfirm]);

  const selected = useMemo(
    () => LEVELS.find((entry) => entry.level === level) ?? LEVELS[3],
    [level]
  );
  const selectedMaterial = useMemo(
    () => MATERIALS.find((entry) => entry.name === material) ?? MATERIALS[0],
    [material]
  );

  const materialItems = selected.blocks * 9;

  const resetAll = useCallback(() => {
    setLevel(DEFAULTS.level);
    setMaterial(DEFAULTS.material);
  }, []);

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-5xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Beacon Pyramid
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Plan beacon pyramid blocks, items, layers, and range
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Squircle
            cornerRadius={32}
            className="p-6 bg-paper-2 shadow-sm animate-in"
            style={{ animationDelay: "0.1s" }}
          >
            <div className="flex items-center justify-between mb-4">
              <label className="text-sm font-bold text-gray-700 dark:text-gray-300">
                Pyramid
              </label>
              <button
                onClick={() => setShowResetConfirm(true)}
                aria-label="Reset all settings"
                className="flex items-center gap-1.5 text-xs font-bold text-gray-400 hover:text-orange-500 transition-colors cursor-pointer"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                Reset
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <span className="block text-sm font-bold text-gray-600 dark:text-gray-400 mb-1.5">
                  Beacon Level
                </span>
                <div className="grid grid-cols-4 gap-1">
                  {LEVELS.map((entry) => (
                    <button
                      key={entry.level}
                      onClick={() => setLevel(entry.level)}
                      className={`py-2 rounded-xl text-sm font-bold cursor-pointer transition-colors ${
                        level === entry.level
                          ? "bg-orange-500 text-white"
                          : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                      }`}
                    >
                      {entry.level}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <span className="block text-sm font-bold text-gray-600 dark:text-gray-400 mb-1.5">
                  Material
                </span>
                <div className="grid grid-cols-1 gap-2">
                  {MATERIALS.map((entry) => (
                    <button
                      key={entry.name}
                      onClick={() => setMaterial(entry.name)}
                      className={`flex items-center justify-between rounded-xl px-3 py-2 text-sm font-bold cursor-pointer transition-colors ${
                        material === entry.name
                          ? "bg-orange-500 text-white"
                          : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                      }`}
                    >
                      <span>{entry.name}</span>
                      <PixelIcon
                        src={entry.texture}
                        size={24}
                      />
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </Squircle>

          <div className="lg:col-span-2 space-y-6">
            <Squircle
              cornerRadius={32}
              className="p-6 bg-paper-2 shadow-sm animate-in"
              style={{ animationDelay: "0.15s" }}
            >
              <div className="mb-5">
                <div>
                  <span className="text-sm font-bold text-gray-500 dark:text-gray-400">
                    Level {selected.level} Beacon
                  </span>
                  <p className="mt-2 text-2xl lg:text-3xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(selected.blocks)} {material} Blocks
                  </p>
                </div>
              </div>

              <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
                <div className="min-w-0 rounded-2xl bg-paper p-4">
                  <Boxes className="w-5 h-5 text-orange-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(selected.blocks)}
                  </p>
                  <p className="text-xs font-bold text-gray-400 truncate">Blocks</p>
                </div>
                <div className="min-w-0 rounded-2xl bg-paper p-4">
                  <PixelIcon
                    src={selectedMaterial.texture}
                    size={24}
                    className="mb-2"
                  />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(materialItems)}
                  </p>
                  <p className="text-xs font-bold text-gray-400 truncate">
                    {selectedMaterial.itemName}
                  </p>
                </div>
                <div className="min-w-0 rounded-2xl bg-paper p-4">
                  <Sparkles className="w-5 h-5 text-blue-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {selected.range}
                  </p>
                  <p className="text-xs font-bold text-gray-400 truncate">Block Range</p>
                </div>
                <div className="min-w-0 rounded-2xl bg-paper p-4">
                  <Package className="w-5 h-5 text-purple-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {compactStackText(materialItems)}
                  </p>
                  <p className="text-xs font-bold text-gray-400 truncate">Stacks</p>
                </div>
              </div>
            </Squircle>

            <div className="grid grid-cols-1 gap-6">
              <Squircle
                cornerRadius={32}
                className="p-6 bg-paper-2 shadow-sm animate-in"
                style={{ animationDelay: "0.2s" }}
              >
                <div className="flex items-center gap-2 mb-4">
                  <div className="p-1.5 rounded-lg bg-orange-500/10">
                    <Boxes className="w-4 h-4 text-orange-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Layer Preview
                  </h2>
                </div>
                <div className="flex flex-col-reverse items-center gap-2">
                  {selected.layers.map((size) => (
                    <div
                      key={size}
                      className="rounded-xl bg-orange-500 text-white text-xs font-bold py-2 text-center shadow-sm"
                      style={{ width: `${(size / 9) * 100}%` }}
                    >
                      {size}x{size} ({size * size})
                    </div>
                  ))}
                </div>
              </Squircle>
            </div>
          </div>
        </div>
      </div>

      {showResetConfirm && (
        <div
          className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-[fadeIn_0.15s_ease-out]"
          onClick={() => setShowResetConfirm(false)}
        >
          <div
            className="bg-paper-2 rounded-2xl p-6 max-w-sm w-full shadow-2xl animate-[scaleIn_0.2s_ease-out] text-center"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-lg font-bold text-gray-800 dark:text-gray-100 mb-2">
              Reset everything?
            </h2>
            <p className="text-sm text-gray-500 mb-6">
              Are you sure you'd like to reset all settings back to default?
            </p>
            <div className="flex gap-3">
              <button
                onClick={() => setShowResetConfirm(false)}
                className="flex-1 py-2.5 rounded-xl bg-paper hover:bg-line text-gray-700 dark:text-gray-300 font-bold text-sm cursor-pointer transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  resetAll();
                  setShowResetConfirm(false);
                }}
                className="flex-1 py-2.5 rounded-xl bg-red-500 hover:bg-red-600 text-white font-bold text-sm cursor-pointer transition-colors"
              >
                Reset
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
