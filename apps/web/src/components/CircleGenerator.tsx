"use client";

import { useState, useMemo, useEffect, useCallback, useRef } from "react";
import Squircle from "@/components/Squircle";
import { RotateCcw, Link2, Unlink2 } from "lucide-react";

const STORAGE_KEY = "crabcraft-circle-gen";

type Mode = "outline" | "filled" | "thick";

const DEFAULTS = {
  width: 15,
  height: 15,
  mode: "thick" as Mode,
  linked: true,
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

export default function CircleGenerator() {
  const [width, setWidth] = useState(DEFAULTS.width);
  const [height, setHeight] = useState(DEFAULTS.height);
  const [mode, setMode] = useState<Mode>(DEFAULTS.mode);
  const [linked, setLinked] = useState(DEFAULTS.linked);
  const [hydrated, setHydrated] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);

  // Load saved state
  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      // Support legacy `diameter` field
      if (saved.width) setWidth(saved.width);
      else if (saved.diameter) setWidth(saved.diameter);
      if (saved.height) setHeight(saved.height);
      else if (saved.diameter) setHeight(saved.diameter);
      if (saved.mode) setMode(saved.mode);
      if (saved.linked !== undefined) setLinked(saved.linked);
    }
    setHydrated(true);
  }, []);

  // Persist state
  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ width, height, mode, linked })
    );
  }, [width, height, mode, linked, hydrated]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && showResetConfirm) setShowResetConfirm(false);
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [showResetConfirm]);

  const resetAll = useCallback(() => {
    setWidth(DEFAULTS.width);
    setHeight(DEFAULTS.height);
    setMode(DEFAULTS.mode);
    setLinked(DEFAULTS.linked);
  }, []);

  const updateWidth = useCallback(
    (v: number) => {
      const clamped = Math.max(1, Math.min(128, v));
      setWidth(clamped);
      if (linked) setHeight(clamped);
    },
    [linked]
  );

  const updateHeight = useCallback(
    (v: number) => {
      const clamped = Math.max(1, Math.min(128, v));
      setHeight(clamped);
      if (linked) setWidth(clamped);
    },
    [linked]
  );

  // Generate grid
  const { grid, blockCount } = useMemo(() => {
    const g: boolean[][] = Array.from({ length: height }, () =>
      Array(width).fill(false)
    );
    const rx = width / 2;
    const ry = height / 2;
    let count = 0;

    // Normalised ellipse test: (dx/rx)² + (dy/ry)² <= 1
    const inside = (x: number, y: number) => {
      const dx = x + 0.5 - rx;
      const dy = y + 0.5 - ry;
      return (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) <= 1;
    };

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        let on = false;
        if (mode === "filled") {
          on = inside(x, y);
        } else {
          // Boundary-check: inside but has at least one outside 4-neighbor
          on =
            inside(x, y) &&
            (x === 0 || !inside(x - 1, y) ||
             x === width - 1 || !inside(x + 1, y) ||
             y === 0 || !inside(x, y - 1) ||
             y === height - 1 || !inside(x, y + 1));
        }

        if (on) {
          g[y][x] = true;
          count++;
        }
      }
    }

    // Thick mode: fill diagonal corners for 4-connectivity
    if (mode === "thick") {
      for (let y = 0; y < height - 1; y++) {
        for (let x = 0; x < width - 1; x++) {
          if (g[y][x] && g[y + 1][x + 1] && !g[y][x + 1] && !g[y + 1][x]) {
            // Pick bridge cell closer to center (inside)
            const d1 =
              ((x + 1.5 - rx) / rx) ** 2 + ((y + 0.5 - ry) / ry) ** 2;
            const d2 =
              ((x + 0.5 - rx) / rx) ** 2 + ((y + 1.5 - ry) / ry) ** 2;
            if (d1 <= d2) {
              g[y][x + 1] = true;
            } else {
              g[y + 1][x] = true;
            }
            count++;
          }
          if (g[y][x + 1] && g[y + 1][x] && !g[y][x] && !g[y + 1][x + 1]) {
            const d1 =
              ((x + 0.5 - rx) / rx) ** 2 + ((y + 0.5 - ry) / ry) ** 2;
            const d2 =
              ((x + 1.5 - rx) / rx) ** 2 + ((y + 1.5 - ry) / ry) ** 2;
            if (d1 <= d2) {
              g[y][x] = true;
            } else {
              g[y + 1][x + 1] = true;
            }
            count++;
          }
        }
      }
    }

    return { grid: g, blockCount: count };
  }, [width, height, mode]);

  // Cell size adapts to largest dimension
  const cellSize = Math.max(4, Math.min(24, Math.floor(600 / Math.max(width, height))));

  const canvasRef = useRef<HTMLCanvasElement>(null);

  // Paint canvas
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const gap = 1;
    const cw = width * (cellSize + gap) + gap;
    const ch = height * (cellSize + gap) + gap;
    canvas.width = cw;
    canvas.height = ch;
    const ctx = canvas.getContext("2d")!;

    const dark = document.documentElement.classList.contains("dark");
    ctx.fillStyle = "rgba(128,128,128,0.15)";
    ctx.fillRect(0, 0, cw, ch);

    const bgColor = dark ? "#1a1412" : "#fbf6ee";
    const onColor = "#f97316";
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        ctx.fillStyle = grid[y][x] ? onColor : bgColor;
        ctx.fillRect(
          gap + x * (cellSize + gap),
          gap + y * (cellSize + gap),
          cellSize,
          cellSize
        );
      }
    }
  }, [grid, width, height, cellSize]);

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-4xl">
        {/* Header */}
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Circle Generator
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Generate pixel-perfect Minecraft circles and ovals for building
          </p>
        </div>

        {/* Settings */}
        <Squircle
          cornerRadius={32}
          className="p-6 bg-paper-2 shadow-sm animate-in mb-6"
          style={{ animationDelay: "0.1s" }}
        >
          <div className="flex items-center justify-between mb-4">
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

          {/* Width & Height with link toggle */}
          <div className="flex items-end gap-3 mb-4">
            {/* Width */}
            <div className="flex-1">
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-sm font-bold text-gray-600 dark:text-gray-400">
                  Width
                </span>
                <input
                  type="number"
                  min={1}
                  max={128}
                  value={width}
                  onChange={(e) => updateWidth(Number(e.target.value) || 1)}
                  className="w-16 text-right text-sm font-bold bg-gray-100 dark:bg-white/10 rounded-lg px-2 py-1 text-gray-700 dark:text-gray-200 outline-none"
                />
              </div>
              <input
                type="range"
                min={1}
                max={128}
                value={width}
                onChange={(e) => updateWidth(Number(e.target.value))}
                className="w-full cursor-pointer"
              />
            </div>

            {/* Link/Unlink toggle */}
            <button
              onClick={() => setLinked((l) => !l)}
              aria-label={linked ? "Unlink dimensions" : "Link dimensions"}
              className={`mb-1 p-2 rounded-lg transition-colors cursor-pointer ${
                linked
                  ? "bg-orange-500/10 text-orange-500"
                  : "bg-gray-100 dark:bg-white/10 text-gray-400 hover:text-orange-500"
              }`}
            >
              {linked ? (
                <Link2 className="w-4 h-4" />
              ) : (
                <Unlink2 className="w-4 h-4" />
              )}
            </button>

            {/* Height */}
            <div className="flex-1">
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-sm font-bold text-gray-600 dark:text-gray-400">
                  Height
                </span>
                <input
                  type="number"
                  min={1}
                  max={128}
                  value={height}
                  onChange={(e) => updateHeight(Number(e.target.value) || 1)}
                  className="w-16 text-right text-sm font-bold bg-gray-100 dark:bg-white/10 rounded-lg px-2 py-1 text-gray-700 dark:text-gray-200 outline-none"
                />
              </div>
              <input
                type="range"
                min={1}
                max={128}
                value={height}
                onChange={(e) => updateHeight(Number(e.target.value))}
                className="w-full cursor-pointer"
              />
            </div>
          </div>

          {/* Mode toggle */}
          <div>
            <span className="block text-sm font-bold text-gray-600 dark:text-gray-400 mb-1.5">
              Mode
            </span>
            <div className="flex gap-1">
              {(["thick", "outline", "filled"] as Mode[]).map((m) => (
                <button
                  key={m}
                  onClick={() => setMode(m)}
                  className={`flex-1 py-1.5 px-3 rounded-lg text-sm font-bold cursor-pointer transition-colors capitalize ${
                    mode === m
                      ? "bg-orange-500 text-white"
                      : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                  }`}
                >
                  {m}
                </button>
              ))}
            </div>
          </div>
        </Squircle>

        {/* Preview */}
        <Squircle
          cornerRadius={32}
          className="p-6 bg-paper-2 shadow-sm animate-in"
          style={{ animationDelay: "0.15s" }}
        >
          <div className="flex items-center justify-between mb-4">
            <span className="text-sm font-bold text-gray-700 dark:text-gray-300">
              Preview
            </span>
            <span className="text-sm font-bold text-gray-500 dark:text-gray-400">
              {blockCount} blocks ({Math.floor(blockCount / 64)}s{" "}
              {blockCount % 64}e) &middot; {width}&times;{height}
            </span>
          </div>

          <div className="-mx-6 -mb-6">
            <canvas
              ref={canvasRef}
              className="w-full rounded-b-[32px] pixelated"
            />
          </div>
        </Squircle>
      </div>

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
                className="flex-1 py-2.5 rounded-xl bg-gray-100 dark:bg-[#2a221b] hover:bg-gray-200 dark:hover:bg-[#3d3028] text-gray-700 dark:text-gray-300 font-bold text-sm cursor-pointer transition-colors"
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
