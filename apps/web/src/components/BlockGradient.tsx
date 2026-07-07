"use client";

import { useState, useMemo, useEffect, useCallback, useRef } from "react";
import SwatchColorPicker from "./SwatchColorPicker";
import Squircle from "@/components/Squircle";
import {
  interpolateOklab,
  colorDistance,
  colorDistanceLab,
  interpolateOklabValues,
  interpolateOklchValues,
  hexToRgb,
  srgbToOklab,
  oklabToSrgb,
  rgbToHex,
} from "@/lib/colors";
import { RotateCcw, ArrowLeftRight, ChevronDown, Check } from "lucide-react";
import blocks from "@/data/blocks.json";
import {
  BLOCK_GRADIENT_PRESETS,
  isBlockAllowedForPreset,
  isBlockGradientPresetId,
  type BlockGradientPresetId,
} from "@/lib/blockGradientPresets";

const TEXTURE_BASE = "/textures/blocks";

const STORAGE_KEY = "crabcraft-block-gradient";

interface GradientEndpoint {
  mode: "color" | "block";
  color: string;
  blockId: string | null;
  blockName: string | null;
  blockTexture: string | null;
}

type BlockWithLab = (typeof blocks)[number] & {
  lab: [number, number, number];
};

function loadSaved() {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

// Hash function for 2D noise grid points
function hash2d(x: number, y: number, seed: number): number {
  const h = Math.sin(x * 127.1 + y * 311.7 + seed * 9301) * 43758.5453;
  return h - Math.floor(h);
}

// 2D value noise — produces organic, spatially-correlated perturbation
function valueNoise2d(x: number, y: number, seed: number): number {
  const ix = Math.floor(x);
  const iy = Math.floor(y);
  const fx = x - ix;
  const fy = y - iy;

  // Smoothstep for natural interpolation
  const sx = fx * fx * (3 - 2 * fx);
  const sy = fy * fy * (3 - 2 * fy);

  // Random values at 4 corners
  const n00 = hash2d(ix, iy, seed);
  const n10 = hash2d(ix + 1, iy, seed);
  const n01 = hash2d(ix, iy + 1, seed);
  const n11 = hash2d(ix + 1, iy + 1, seed);

  // Bilinear interpolation
  const nx0 = n00 + (n10 - n00) * sx;
  const nx1 = n01 + (n11 - n01) * sx;
  return nx0 + (nx1 - nx0) * sy;
}

export default function BlockGradient() {
  const [start, setStart] = useState<GradientEndpoint>({
    mode: "block",
    color: "#E79B33",
    blockId: "honeycomb_block",
    blockName: "Honeycomb Block",
    blockTexture: "honeycomb",
  });
  const [end, setEnd] = useState<GradientEndpoint>({
    mode: "block",
    color: "#611C24",
    blockId: "crimson_stem",
    blockName: "Crimson Stem",
    blockTexture: "huge_fungus/crimson_log_side",
  });
  const [steps, setSteps] = useState(12);
  const [randomness, setRandomness] = useState(20);
  const [gradientLength, setGradientLength] = useState(7);
  const [blockPreset, setBlockPreset] = useState<BlockGradientPresetId>("all");
  const [excludedIds, setExcludedIds] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const [presetMenuOpen, setPresetMenuOpen] = useState(false);

  // Block picker modal state
  const [blockPickerFor, setBlockPickerFor] = useState<
    "start" | "end" | null
  >(null);
  const [blockSearch, setBlockSearch] = useState("");
  const [tooltip, setTooltip] = useState<{
    name: string;
    texture?: string;
    subtitle?: string;
    x: number;
    y: number;
  } | null>(null);

  // Wall block info popover
  const [wallPopover, setWallPopover] = useState<{
    block: BlockWithLab;
    x: number;
    y: number;
    cellKey: string;
  } | null>(null);

  const presetMenuRef = useRef<HTMLDivElement>(null);

  // Load saved state after hydration
  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      const hydrateEndpoint = (s: any): GradientEndpoint | null => {
        if (!s || !s.mode || !s.color) return null;
        // Reconstruct block name/texture from blocks list if missing
        if (s.blockId && (!s.blockName || !s.blockTexture)) {
          const b = blocks.find((bl: any) => bl.id === s.blockId);
          if (b) {
            s.blockName = b.name;
            s.blockTexture = b.texture;
          }
        }
        return s as GradientEndpoint;
      };
      const savedStart = hydrateEndpoint(saved.start);
      const savedEnd = hydrateEndpoint(saved.end);
      if (savedStart) setStart(savedStart);
      if (savedEnd) setEnd(savedEnd);
      if (saved.steps) setSteps(saved.steps);
      if (saved.randomness !== undefined) setRandomness(saved.randomness);
      if (saved.gradientLength) setGradientLength(saved.gradientLength);
      if (isBlockGradientPresetId(saved.blockPreset)) {
        setBlockPreset(saved.blockPreset);
      }
      if (saved.excludedIds) setExcludedIds(saved.excludedIds);
    }
    setHydrated(true);
  }, []);

  // Save to localStorage on changes (only after hydration)
  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        start,
        end,
        steps,
        randomness,
        gradientLength,
        blockPreset,
        excludedIds,
      })
    );
  }, [start, end, steps, randomness, gradientLength, blockPreset, excludedIds, hydrated]);

  // Close wall popover on outside click
  useEffect(() => {
    if (!wallPopover) return;
    const handler = () => setWallPopover(null);
    document.addEventListener("click", handler);
    return () => document.removeEventListener("click", handler);
  }, [wallPopover]);

  // Close modals on Escape key
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (presetMenuOpen) setPresetMenuOpen(false);
      else if (showResetConfirm) setShowResetConfirm(false);
      else if (blockPickerFor) { setBlockPickerFor(null); setTooltip(null); }
      else if (wallPopover) setWallPopover(null);
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [presetMenuOpen, showResetConfirm, blockPickerFor, wallPopover]);

  useEffect(() => {
    if (!presetMenuOpen) return;
    const handler = (e: MouseEvent) => {
      if (!presetMenuRef.current?.contains(e.target as Node)) {
        setPresetMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [presetMenuOpen]);

  // Pre-compute OkLAB values for all blocks
  const blocksWithLab = useMemo(() => {
    return blocks.map((block) => {
      const [r, g, b] = hexToRgb(block.color);
      const [L, a, bVal] = srgbToOklab(r, g, b);
      return { ...block, lab: [L, a, bVal] as [number, number, number] };
    });
  }, []);

  const activePreset = useMemo(
    () =>
      BLOCK_GRADIENT_PRESETS.find((preset) => preset.id === blockPreset) ??
      BLOCK_GRADIENT_PRESETS[0],
    [blockPreset]
  );

  const presetBlocks = useMemo(() => {
    return blocks.filter((block) => isBlockAllowedForPreset(block, blockPreset));
  }, [blockPreset]);

  const presetBlocksWithLab = useMemo(() => {
    return blocksWithLab.filter((block) => isBlockAllowedForPreset(block, blockPreset));
  }, [blocksWithLab, blockPreset]);

  // Drop selected block endpoints when the active preset no longer allows them.
  useEffect(() => {
    if (!hydrated) return;

    const sanitizeEndpoint = (endpoint: GradientEndpoint): GradientEndpoint => {
      if (!endpoint.blockId) return endpoint;
      const block = blocks.find((b) => b.id === endpoint.blockId);
      if (block && isBlockAllowedForPreset(block, blockPreset)) return endpoint;
      return {
        ...endpoint,
        mode: "color",
        blockId: null,
        blockName: null,
        blockTexture: null,
      };
    };

    setStart((current) => sanitizeEndpoint(current));
    setEnd((current) => sanitizeEndpoint(current));
  }, [blockPreset, hydrated]);

  // Available blocks for gradient matching (excluding preset-hidden, user-excluded + glazed terracotta)
  const availableBlocks = useMemo(() => {
    const excluded = new Set(excludedIds);
    return presetBlocksWithLab.filter(
      (b) => !excluded.has(b.id) && !b.id.endsWith("_glazed_terracotta")
    );
  }, [presetBlocksWithLab, excludedIds]);

  // Pre-compute start/end OkLAB values
  const startLab = useMemo(
    () => srgbToOklab(...hexToRgb(start.color)),
    [start.color]
  );
  const endLab = useMemo(
    () => srgbToOklab(...hexToRgb(end.color)),
    [end.color]
  );

  // Find closest block to an OkLAB color
  const findClosest = useCallback(
    (targetLab: [number, number, number]): BlockWithLab => {
      const fallbackBlock = blocksWithLab[0];
      if (!fallbackBlock) {
        throw new Error("No blocks are available for gradient matching");
      }
      const searchBlocks =
        availableBlocks.length > 0
          ? availableBlocks
          : presetBlocksWithLab.length > 0
            ? presetBlocksWithLab
            : [fallbackBlock];
      let closest = searchBlocks[0];
      let minDist = Infinity;
      for (const block of searchBlocks) {
        const dist = colorDistanceLab(targetLab, block.lab);
        if (dist < minDist) {
          minDist = dist;
          closest = block;
        }
      }
      return closest;
    },
    [availableBlocks, presetBlocksWithLab, blocksWithLab]
  );

  // Compute gradient: oversample, dedup, then select `steps` unique blocks
  const displayGradient = useMemo(() => {
    const startBlock = start.blockId
      ? presetBlocksWithLab.find((b) => b.id === start.blockId)
      : null;
    const endBlock = end.blockId
      ? presetBlocksWithLab.find((b) => b.id === end.blockId)
      : null;

    // Oversample at high resolution to discover all unique blocks
    const OVERSAMPLE = 100;
    const oversampled: BlockWithLab[] = [];
    for (let i = 0; i < OVERSAMPLE; i++) {
      const t = i / (OVERSAMPLE - 1);
      const lab = interpolateOklchValues(startLab, endLab, t);
      oversampled.push(findClosest(lab));
    }

    // Dedup consecutive duplicates to get all unique blocks in order
    const unique = oversampled.filter(
      (block, i) => i === 0 || block.id !== oversampled[i - 1].id
    );

    // Pin start/end blocks
    if (startBlock) unique[0] = startBlock;
    if (endBlock) unique[unique.length - 1] = endBlock;

    // If we have fewer unique blocks than requested, return all of them
    if (unique.length <= steps) return unique;

    // Select `steps` blocks evenly from the unique list
    const result: BlockWithLab[] = [];
    for (let i = 0; i < steps; i++) {
      const idx = Math.round((i / (steps - 1)) * (unique.length - 1));
      result.push(unique[idx]);
    }
    return result;
  }, [startLab, endLab, steps, findClosest, start.blockId, end.blockId, presetBlocksWithLab]);

  // Build the wall grid with per-cell randomness
  const WALL_COLS = 10;
  const WALL_ROWS = gradientLength;
  const randFactor = randomness / 100;

  const wallGrid = useMemo(() => {
    const blocks = displayGradient;
    const len = blocks.length;
    const rows: BlockWithLab[][] = [];
    for (let r = 0; r < WALL_ROWS; r++) {
      const row: BlockWithLab[] = [];
      for (let c = 0; c < WALL_COLS; c++) {
        let t = WALL_ROWS === 1 ? 0 : r / (WALL_ROWS - 1);

        // Apply randomness: organic 2D noise perturbation
        if (randFactor > 0) {
          const noise = valueNoise2d(c * 0.4, r * 0.4, steps);
          const perturbation = (noise - 0.5) * randFactor;
          t = Math.max(0, Math.min(1, t + perturbation));
        }

        // Map t to a position in the display block list
        const idx = Math.round(t * (len - 1));
        row.push(blocks[idx]);
      }
      rows.push(row);
    }
    return rows;
  }, [
    displayGradient,
    randFactor,
    steps,
    WALL_ROWS,
    WALL_COLS,
  ]);

  // Filtered blocks for modal
  const filteredBlocks = useMemo(() => {
    if (!blockSearch.trim()) return presetBlocks;
    const q = blockSearch.toLowerCase();
    return presetBlocks.filter((b) => b.name.toLowerCase().includes(q));
  }, [blockSearch, presetBlocks]);

  const openBlockPicker = (endpoint: "start" | "end") => {
    setBlockSearch("");
    setBlockPickerFor(endpoint);
  };

  const blockPickerForRef = useRef(blockPickerFor);
  blockPickerForRef.current = blockPickerFor;

  const selectBlock = (block: (typeof blocks)[number]) => {
    const data: GradientEndpoint = {
      mode: "block",
      color: block.color,
      blockId: block.id,
      blockName: block.name,
      blockTexture: block.texture,
    };
    const target = blockPickerForRef.current;
    if (target === "start") {
      setStart(data);
    } else if (target === "end") {
      setEnd(data);
    }
    setBlockPickerFor(null);
    setTooltip(null);
  };

  const excludeBlock = (blockId: string) => {
    if (blockId === start.blockId || blockId === end.blockId) return;
    if (!excludedIds.includes(blockId)) {
      setExcludedIds((prev) => [...prev, blockId]);
    }
    setWallPopover(null);
  };

  const unexcludeBlock = (blockId: string) => {
    setExcludedIds((prev) => prev.filter((id) => id !== blockId));
  };

  const resetAll = () => {
    setStart({ mode: "block", color: "#E79B33", blockId: "honeycomb_block", blockName: "Honeycomb Block", blockTexture: "honeycomb" });
    setEnd({ mode: "block", color: "#611C24", blockId: "crimson_stem", blockName: "Crimson Stem", blockTexture: "huge_fungus/crimson_log_side" });
    setSteps(12);
    setRandomness(20);
    setGradientLength(7);
    setBlockPreset("all");
    setExcludedIds([]);
  };

  const copyBlockList = () => {
    const names = displayGradient.map((b) => b.name).join(", ");
    navigator.clipboard.writeText(names);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  const selectedBlockId =
    blockPickerFor === "start" ? start.blockId : end.blockId;

  if (!hydrated) {
    return <div className="min-h-screen" />;
  }

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-6xl">
        {/* Header */}
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Block Gradient
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Create smooth Minecraft block gradients for building
          </p>
        </div>

        {/* Endpoint pickers */}
        <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] gap-4 md:gap-6 mb-6 items-center">
          {/* Start endpoint */}
          <Squircle
            cornerRadius={32}
            className="p-6 bg-paper-2 shadow-sm animate-in"
            style={{ animationDelay: "0.1s" }}
          >
            <label className="block text-sm font-bold text-gray-700 dark:text-gray-300 mb-3">
              {start.mode === "block" ? "Start Block" : "Start Colour"}
            </label>
            <div className="flex gap-1 mb-4">
              <button
                onClick={() =>
                  setStart((s) => ({ ...s, mode: "color" }))
                }
                className={`flex-1 py-1.5 px-3 rounded-lg text-sm font-bold cursor-pointer transition-colors ${
                  start.mode === "color"
                    ? "bg-orange-500 text-white"
                    : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                }`}
              >
                Colour
              </button>
              <button
                onClick={() => setStart((s) => ({ ...s, mode: "block" }))}
                className={`flex-1 py-1.5 px-3 rounded-lg text-sm font-bold cursor-pointer transition-colors ${
                  start.mode === "block"
                    ? "bg-orange-500 text-white"
                    : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                }`}
              >
                Block
              </button>
            </div>
            {start.mode === "color" && (
              <SwatchColorPicker
                color={start.color}
                ariaLabel="Start colour"
                triggerClassName="w-full flex items-center gap-3 py-2.5 px-3 rounded-xl border-2 border-dashed border-line hover:border-orange-400 transition-colors cursor-pointer"
                onChange={(c) =>
                  setStart((s) =>
                    s.mode === "color"
                      ? { ...s, color: c, blockId: null, blockName: null, blockTexture: null }
                      : { ...s, color: c }
                  )
                }
              >
                <div className="w-8 h-8 flex-shrink-0 rounded border border-black/10 dark:border-white/15" style={{ backgroundColor: start.color }} />
                <span className="text-sm font-bold text-gray-700 dark:text-gray-200">
                  {start.color.toUpperCase()}
                </span>
              </SwatchColorPicker>
            )}
            {/* Block picker conditionally rendered */}
            {start.mode === "block" && (
              <button
                onClick={() => openBlockPicker("start")}
                className="w-full flex items-center gap-3 py-2.5 px-3 rounded-xl border-2 border-dashed border-line hover:border-orange-400 text-gray-500 hover:text-orange-500 transition-colors cursor-pointer"
              >
                {start.blockTexture ? (
                  <div key={start.blockTexture} className="w-8 h-8 flex-shrink-0 rounded overflow-hidden">
                    <img
                      src={`${TEXTURE_BASE}/${start.blockTexture}.png`}
                      alt={start.blockName || ""}
                      className="w-full h-full block-texture"
                    />
                  </div>
                ) : null}
                <span className="text-sm font-bold text-gray-700 dark:text-gray-200">
                  {start.blockName || "Pick Block"}
                </span>
              </button>
            )}
          </Squircle>

          {/* Swap button */}
          <button
            onClick={() => { setStart(end); setEnd(start); }}
            aria-label="Swap start and end"
            className="hidden md:flex w-10 h-10 items-center justify-center rounded-full bg-gray-100 dark:bg-white/10 text-gray-400 hover:text-orange-500 hover:bg-orange-500/10 transition-colors cursor-pointer animate-in"
            style={{ animationDelay: "0.125s" }}
          >
            <ArrowLeftRight className="w-4 h-4" />
          </button>

          {/* End endpoint */}
          <Squircle
            cornerRadius={32}
            className="p-6 bg-paper-2 shadow-sm animate-in"
            style={{ animationDelay: "0.15s" }}
          >
            <label className="block text-sm font-bold text-gray-700 dark:text-gray-300 mb-3">
              {end.mode === "block" ? "End Block" : "End Colour"}
            </label>
            <div className="flex gap-1 mb-4">
              <button
                onClick={() =>
                  setEnd((s) => ({ ...s, mode: "color" }))
                }
                className={`flex-1 py-1.5 px-3 rounded-lg text-sm font-bold cursor-pointer transition-colors ${
                  end.mode === "color"
                    ? "bg-orange-500 text-white"
                    : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                }`}
              >
                Colour
              </button>
              <button
                onClick={() => setEnd((s) => ({ ...s, mode: "block" }))}
                className={`flex-1 py-1.5 px-3 rounded-lg text-sm font-bold cursor-pointer transition-colors ${
                  end.mode === "block"
                    ? "bg-orange-500 text-white"
                    : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                }`}
              >
                Block
              </button>
            </div>
            {end.mode === "color" && (
              <SwatchColorPicker
                color={end.color}
                ariaLabel="End colour"
                triggerClassName="w-full flex items-center gap-3 py-2.5 px-3 rounded-xl border-2 border-dashed border-line hover:border-orange-400 transition-colors cursor-pointer"
                onChange={(c) =>
                  setEnd((s) =>
                    s.mode === "color"
                      ? { ...s, color: c, blockId: null, blockName: null, blockTexture: null }
                      : { ...s, color: c }
                  )
                }
              >
                <div className="w-8 h-8 flex-shrink-0 rounded border border-black/10 dark:border-white/15" style={{ backgroundColor: end.color }} />
                <span className="text-sm font-bold text-gray-700 dark:text-gray-200">
                  {end.color.toUpperCase()}
                </span>
              </SwatchColorPicker>
            )}
            {/* Block picker conditionally rendered */}
            {end.mode === "block" && (
              <button
                onClick={() => openBlockPicker("end")}
                className="w-full flex items-center gap-3 py-2.5 px-3 rounded-xl border-2 border-dashed border-line hover:border-orange-400 text-gray-500 hover:text-orange-500 transition-colors cursor-pointer"
              >
                {end.blockTexture ? (
                  <div key={end.blockTexture} className="w-8 h-8 flex-shrink-0 rounded overflow-hidden">
                    <img
                      src={`${TEXTURE_BASE}/${end.blockTexture}.png`}
                      alt={end.blockName || ""}
                      className="w-full h-full block-texture"
                    />
                  </div>
                ) : null}
                <span className="text-sm font-bold text-gray-700 dark:text-gray-200">
                  {end.blockName || "Pick Block"}
                </span>
              </button>
            )}
          </Squircle>
        </div>

        {/* Steps & settings */}
        {/* Result section */}
        <Squircle
          cornerRadius={32}
          className="p-6 bg-paper-2 shadow-sm animate-in"
          style={{ animationDelay: "0.2s" }}
        >
          <div className="flex items-center justify-between mb-3">
            <label className="text-sm font-bold text-gray-700 dark:text-gray-300">
              Settings
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

          {/* Preset filter */}
          <div className="mb-4">
            <div ref={presetMenuRef} className="relative">
              <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 mb-1">
                Preset
              </label>
              <button
                type="button"
                onClick={() => setPresetMenuOpen((open) => !open)}
                aria-haspopup="listbox"
                aria-expanded={presetMenuOpen}
                className="w-full flex items-center justify-between gap-2 px-3 py-2.5 rounded-xl border border-line bg-paper text-sm font-bold text-gray-700 dark:text-gray-200 hover:bg-paper/80 focus:outline-none focus:border-orange-400 transition-colors cursor-pointer"
              >
                <span className="truncate">{activePreset.name}</span>
                <ChevronDown
                  className={`w-4 h-4 text-gray-400 transition-transform ${
                    presetMenuOpen ? "rotate-180" : ""
                  }`}
                />
              </button>
              {presetMenuOpen && (
                <div
                  role="listbox"
                  className="absolute left-0 right-0 top-full mt-2 z-30 bg-paper-2 rounded-xl shadow-lg overflow-hidden animate-[scaleIn_0.15s_ease-out]"
                >
                  {BLOCK_GRADIENT_PRESETS.map((preset) => {
                    const selected = preset.id === blockPreset;
                    return (
                      <button
                        key={preset.id}
                        type="button"
                        role="option"
                        aria-selected={selected}
                        onClick={() => {
                          setBlockPreset(preset.id);
                          setPresetMenuOpen(false);
                        }}
                        className={`w-full flex items-center justify-between gap-3 px-3 py-2.5 text-left text-sm font-bold transition-colors cursor-pointer ${
                          selected
                            ? "bg-orange-500/10 text-orange-500"
                            : "text-gray-700 dark:text-gray-300 hover:bg-paper"
                        }`}
                      >
                        <span>{preset.name}</span>
                        {selected && <Check className="w-4 h-4" />}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
            <p className="mt-1.5 text-xs text-gray-400 dark:text-gray-500">
              {activePreset.description} Matching from {availableBlocks.length} blocks.
            </p>
          </div>

          {/* Excluded blocks */}
          {excludedIds.length > 0 && (
            <div className="mb-4">
              <p className="text-xs font-bold text-gray-500 dark:text-gray-400 mb-2">
                Excluded ({excludedIds.length})
              </p>
              <div className="flex flex-wrap gap-1.5">
                {excludedIds.map((id) => {
                  const block = blocks.find((b) => b.id === id);
                  if (!block) return null;
                  return (
                    <button
                      key={id}
                      onClick={() => unexcludeBlock(id)}
                      className="inline-flex items-center gap-1.5 px-2 py-1 rounded-lg bg-red-500/10 text-[11px] font-medium text-red-500 hover:bg-red-500/20 transition-colors cursor-pointer"
                    >
                      <div className="w-3.5 h-3.5 flex-shrink-0 overflow-hidden">
                        <img
                          src={`${TEXTURE_BASE}/${block.texture}.png`}
                          alt=""
                          className="w-full h-full block-texture"
                        />
                      </div>
                      {block.name}
                      <span className="text-[10px] opacity-70">✕</span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Blocks slider */}
          <div className="mb-4 pt-4 border-t border-line">
            <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 mb-1">
              Blocks: {steps}
            </label>
            <input
              type="range"
              min={3}
              max={22}
              value={steps}
              onChange={(e) => setSteps(Number(e.target.value))}
              className="w-full cursor-pointer"
            />
          </div>

          {/* Block list */}
          <div className="mb-2">
            <p className="text-xs font-bold text-gray-500 dark:text-gray-400">Block List ({displayGradient.length})</p>
          </div>
          <div className="grid gap-1.5" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(80px, 1fr))" }}>
            {displayGradient.map((block, i) => (
              <button
                key={`${block.id}-${i}`}
                onClick={() => excludeBlock(block.id)}
                onMouseEnter={(e) => {
                  const rect = e.currentTarget.getBoundingClientRect();
                  setTooltip({
                    name: block.name,
                    texture: block.texture,
                    subtitle: block.id === start.blockId || block.id === end.blockId ? undefined : "Click to exclude",
                    x: rect.left + rect.width / 2,
                    y: rect.top - 4,
                  });
                }}
                onMouseLeave={() => setTooltip(null)}
                className="rounded-lg overflow-hidden cursor-pointer transition-all hover:brightness-125 hover:scale-110 hover:z-10 aspect-square"
              >
                <img
                  src={`${TEXTURE_BASE}/${block.texture}.png`}
                  alt={block.name}
                  className="w-full h-full block-texture"
                />
              </button>
            ))}
          </div>

          {/* Copy button */}
          <button
            onClick={copyBlockList}
            className="w-full mt-4 bg-orange-500 hover:bg-orange-600 text-white font-bold py-3 px-4 rounded-xl transition-colors cursor-pointer active:scale-95"
          >
            {copied ? "Copied!" : "Copy Block List"}
          </button>

        </Squircle>

        {/* Preview */}
        <Squircle
          cornerRadius={32}
          className="mt-6 bg-paper-2 shadow-sm animate-in overflow-hidden"
          style={{ animationDelay: "0.25s" }}
        >
          <div className="px-6 pt-6 pb-3">
            <label className="block text-sm font-bold text-gray-700 dark:text-gray-300">
              Preview
            </label>
            <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
              Click a block to add it to your exclusion list
            </p>
          </div>
          <div className="relative">
            {wallGrid.map((row, r) => (
              <div key={r} className="flex">
                {row.map((block, c) => (
                  <img
                    key={`${r}-${c}`}
                    src={`${TEXTURE_BASE}/${block.texture}.png`}
                    alt=""
                    className="flex-1 min-w-0 cursor-pointer hover:brightness-125 transition-[filter] block-texture"
                    style={{ aspectRatio: "1" }}
                    onClick={(e) => {
                      e.stopPropagation();
                      const key = `${r}-${c}`;
                      if (wallPopover?.cellKey === key) {
                        setWallPopover(null);
                      } else {
                        const rect = e.currentTarget.getBoundingClientRect();
                        setWallPopover({
                          block,
                          x: rect.left + rect.width / 2,
                          y: rect.top - 4,
                          cellKey: key,
                        });
                      }
                    }}
                  />
                ))}
              </div>
            ))}
          </div>
          <div className="px-6 py-4 flex flex-col gap-4 border-t border-line">
            {/* Randomness slider */}
            <div>
              <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 mb-1">
                Randomness: {randomness}%
              </label>
              <input
                type="range"
                min={0}
                max={100}
                value={randomness}
                onChange={(e) => setRandomness(Number(e.target.value))}
                className="w-full cursor-pointer"
              />
            </div>
            {/* Gradient length slider */}
            <div>
              <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 mb-1">
                Gradient Length: {gradientLength} blocks
              </label>
              <input
                type="range"
                min={3}
                max={30}
                value={gradientLength}
                onChange={(e) => setGradientLength(Number(e.target.value))}
                className="w-full cursor-pointer"
              />
            </div>
          </div>
        </Squircle>
      </div>

      {/* Block Picker Modal */}
      {blockPickerFor && (
        <div
          className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-[fadeIn_0.15s_ease-out]"
          onClick={() => {
            setBlockPickerFor(null);
            setTooltip(null);
          }}
        >
          <div
            className="bg-paper-2 rounded-2xl p-6 max-w-2xl w-full shadow-2xl animate-[scaleIn_0.2s_ease-out] flex flex-col"
            style={{ maxHeight: "80vh" }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-4">
              <div>
                <h2 className="text-lg font-bold text-gray-800 dark:text-gray-100">
                  Pick a Block
                </h2>
                <p className="text-xs text-gray-400 dark:text-gray-500">
                  {activePreset.name} preset
                </p>
              </div>
              <button
                onClick={() => {
                  setBlockPickerFor(null);
                  setTooltip(null);
                }}
                aria-label="Close"
                className="w-8 h-8 rounded-lg bg-gray-100 dark:bg-white/10 hover:bg-gray-200 dark:hover:bg-white/20 flex items-center justify-center text-gray-500 cursor-pointer transition-colors"
              >
                ✕
              </button>
            </div>
            <input
              type="search"
              aria-label="Search blocks"
              placeholder="Search blocks..."
              value={blockSearch}
              onChange={(e) => setBlockSearch(e.target.value)}
              autoFocus
              className="w-full px-4 py-2.5 rounded-xl border border-line bg-paper text-sm focus:outline-none focus:border-orange-400 mb-4"
            />
            <div
              className="overflow-y-auto flex-1 -mx-1 px-1 themed-scrollbar"
              onMouseLeave={() => setTooltip(null)}
            >
              <div className="grid grid-cols-6 sm:grid-cols-8 gap-2 p-1">
                {filteredBlocks.map((block) => (
                  <button
                    key={block.id}
                    onClick={() => selectBlock(block)}
                    onMouseEnter={(e) => {
                      const rect = e.currentTarget.getBoundingClientRect();
                      setTooltip({
                        name: block.name,
                        x: rect.left + rect.width / 2,
                        y: rect.top - 4,
                      });
                    }}
                    onMouseLeave={() => setTooltip(null)}
                    className={`rounded-lg overflow-hidden cursor-pointer transition-transform hover:scale-110 hover:z-10 ${
                      selectedBlockId === block.id
                        ? "ring-2 ring-orange-500 ring-offset-1 ring-offset-paper-2"
                        : ""
                    }`}
                    style={{ backgroundColor: block.color }}
                  >
                    <img
                      src={`${TEXTURE_BASE}/${block.texture}.png`}
                      alt={block.name}
                      width={40}
                      height={40}
                      className="w-full aspect-square block-texture"
                    />
                  </button>
                ))}
              </div>
              {filteredBlocks.length === 0 && (
                <p className="text-sm text-gray-400 text-center py-8">
                  No blocks found for &ldquo;{blockSearch}&rdquo;
                </p>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Fixed tooltip for block picker */}
      {tooltip && (
        <div
          className="fixed z-[60] pointer-events-none -translate-x-1/2 -translate-y-full whitespace-nowrap animate-[fadeIn_0.1s_ease-out]"
          style={{ left: tooltip.x, top: tooltip.y }}
        >
          <div className="bg-paper-2 rounded-xl shadow-xl border border-line p-3 flex items-center gap-3">
            {tooltip.texture && (
              <div className="w-8 h-8 flex-shrink-0 rounded overflow-hidden">
                <img
                  src={`${TEXTURE_BASE}/${tooltip.texture}.png`}
                  alt=""
                  className="w-full h-full block-texture"
                />
              </div>
            )}
            <div>
              <p className="text-sm font-bold text-gray-800 dark:text-gray-200">
                {tooltip.name}
              </p>
              {tooltip.subtitle && (
                <p className="text-[11px] text-gray-400 font-bold mt-0.5">
                  {tooltip.subtitle}
                </p>
              )}
            </div>
          </div>
          <div className="flex justify-center">
            <div className="w-2 h-2 bg-paper-2 border-b border-r border-line rotate-45 -mt-1.5" />
          </div>
        </div>
      )}

      {/* Wall block popover */}
      {wallPopover && (
        <div
          className="fixed z-[60] -translate-x-1/2 -translate-y-full animate-[scaleIn_0.1s_ease-out]"
          style={{ left: wallPopover.x, top: wallPopover.y }}
        >
          <div className="bg-paper-2 rounded-xl shadow-xl border border-line p-3 flex items-center gap-3">
            <div className="w-8 h-8 flex-shrink-0 rounded overflow-hidden">
              <img
                src={`${TEXTURE_BASE}/${wallPopover.block.texture}.png`}
                alt=""
                className="w-full h-full block-texture"
              />
            </div>
            <div>
              <p className="text-sm font-bold text-gray-800 dark:text-gray-200">
                {wallPopover.block.name}
              </p>
              {wallPopover.block.id !== start.blockId && wallPopover.block.id !== end.blockId && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    excludeBlock(wallPopover.block.id);
                  }}
                  className="text-[11px] text-red-500 hover:text-red-600 font-bold cursor-pointer mt-0.5"
                >
                  Exclude
                </button>
              )}
            </div>
          </div>
          <div className="flex justify-center">
            <div className="w-2 h-2 bg-paper-2 border-b border-r border-line rotate-45 -mt-1.5" />
          </div>
        </div>
      )}

      {/* Reset Confirmation Modal */}
      {showResetConfirm && (
        <div
          className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-[fadeIn_0.15s_ease-out]"
          onClick={() => setShowResetConfirm(false)}
        >
          <div
            className="bg-paper-2 rounded-2xl p-6 max-w-sm w-full shadow-2xl animate-[scaleIn_0.2s_ease-out] text-center"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-lg font-bold text-gray-800 dark:text-gray-100 mb-2">Reset everything?</h2>
            <p className="text-sm text-gray-500 mb-6">Are you sure you'd like to reset all settings back to default?</p>
            <div className="flex gap-3">
              <button
                onClick={() => setShowResetConfirm(false)}
                className="flex-1 py-2.5 rounded-xl bg-paper hover:bg-line text-gray-700 dark:text-gray-300 font-bold text-sm cursor-pointer transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => { resetAll(); setShowResetConfirm(false); }}
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
