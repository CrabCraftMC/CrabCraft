"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Squircle from "@/components/Squircle";
import { BarChart3, Check, Copy, FlaskConical, RotateCcw, Sparkles, Target } from "lucide-react";
import { captureWebToolCompleted } from "@/lib/analytics";

const STORAGE_KEY = "crabcraft-xp-calculator";

const DEFAULTS = {
  currentLevel: 27,
  targetLevel: 30,
  progress: 0,
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

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function totalXpForLevel(level: number) {
  if (level <= 16) return level * level + 6 * level;
  if (level <= 31) return 2.5 * level * level - 40.5 * level + 360;
  return 4.5 * level * level - 162.5 * level + 2220;
}

function xpToNextLevel(level: number) {
  if (level <= 15) return 2 * level + 7;
  if (level <= 30) return 5 * level - 38;
  return 9 * level - 158;
}

function formatAmount(value: number) {
  return new Intl.NumberFormat("en-US").format(value);
}

export default function XPCalculator() {
  const [currentLevel, setCurrentLevel] = useState(DEFAULTS.currentLevel);
  const [targetLevel, setTargetLevel] = useState(DEFAULTS.targetLevel);
  const [progress, setProgress] = useState(DEFAULTS.progress);
  const [hydrated, setHydrated] = useState(false);
  const [copied, setCopied] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);

  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      if (saved.currentLevel !== undefined) {
        setCurrentLevel(clamp(Number(saved.currentLevel) || 0, 0, 100));
      }
      if (saved.targetLevel !== undefined) {
        setTargetLevel(clamp(Number(saved.targetLevel) || 0, 0, 100));
      }
      if (saved.progress !== undefined) {
        setProgress(clamp(Number(saved.progress) || 0, 0, 99));
      }
    }
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ currentLevel, targetLevel, progress })
    );
  }, [currentLevel, targetLevel, progress, hydrated]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && showResetConfirm) setShowResetConfirm(false);
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [showResetConfirm]);

  const currentXp = useMemo(() => {
    return totalXpForLevel(currentLevel) + xpToNextLevel(currentLevel) * (progress / 100);
  }, [currentLevel, progress]);

  const targetXp = useMemo(() => totalXpForLevel(targetLevel), [targetLevel]);
  const neededXp = Math.max(0, Math.ceil(targetXp - currentXp));
  const bottlesAverage = Math.ceil(neededXp / 7);
  const bottlesBest = Math.ceil(neededXp / 11);
  const bottlesWorst = Math.ceil(neededXp / 3);
  const levelsNeeded = Math.max(0, targetLevel - currentLevel);
  const pathPercent =
    targetLevel <= currentLevel ? 100 : (currentLevel / targetLevel) * 100;

  const setCurrent = useCallback((value: number) => {
    const next = clamp(value, 0, 100);
    setCurrentLevel(next);
    if (targetLevel < next) setTargetLevel(next);
  }, [targetLevel]);

  const setTarget = useCallback((value: number) => {
    setTargetLevel(clamp(value, 0, 100));
  }, []);

  const resetAll = useCallback(() => {
    setCurrentLevel(DEFAULTS.currentLevel);
    setTargetLevel(DEFAULTS.targetLevel);
    setProgress(DEFAULTS.progress);
  }, []);

  const copySummary = useCallback(() => {
    navigator.clipboard.writeText(
      `Minecraft XP: level ${currentLevel} (${progress}%) to level ${targetLevel} needs ${neededXp} XP, about ${bottlesAverage} bottles of enchanting on average.`
    );
    captureWebToolCompleted("xp_calculator", "copy_summary");
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }, [bottlesAverage, currentLevel, neededXp, progress, targetLevel]);

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-5xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            XP Levels
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Calculate Minecraft XP needed between levels
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
                Levels
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

            <div className="space-y-5">
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-sm font-bold text-gray-600 dark:text-gray-400">
                    Current Level
                  </span>
                  <input
                    type="number"
                    min={0}
                    max={100}
                    value={currentLevel}
                    onChange={(e) => setCurrent(Number(e.target.value) || 0)}
                    className="w-16 text-right text-sm font-bold bg-gray-100 dark:bg-white/10 rounded-lg px-2 py-1 text-gray-700 dark:text-gray-200 outline-none"
                  />
                </div>
                <input
                  type="range"
                  min={0}
                  max={100}
                  value={currentLevel}
                  onChange={(e) => setCurrent(Number(e.target.value))}
                  className="w-full cursor-pointer"
                />
              </div>

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-sm font-bold text-gray-600 dark:text-gray-400">
                    Target Level
                  </span>
                  <input
                    type="number"
                    min={0}
                    max={100}
                    value={targetLevel}
                    onChange={(e) => setTarget(Number(e.target.value) || 0)}
                    className="w-16 text-right text-sm font-bold bg-gray-100 dark:bg-white/10 rounded-lg px-2 py-1 text-gray-700 dark:text-gray-200 outline-none"
                  />
                </div>
                <input
                  type="range"
                  min={0}
                  max={100}
                  value={targetLevel}
                  onChange={(e) => setTarget(Number(e.target.value))}
                  className="w-full cursor-pointer"
                />
              </div>

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-sm font-bold text-gray-600 dark:text-gray-400">
                    Current Bar
                  </span>
                  <span className="text-sm font-bold text-gray-500 dark:text-gray-400">
                    {progress}%
                  </span>
                </div>
                <input
                  type="range"
                  min={0}
                  max={99}
                  value={progress}
                  onChange={(e) => setProgress(Number(e.target.value))}
                  className="w-full cursor-pointer"
                />
              </div>

              <div className="grid grid-cols-3 gap-2">
                {[15, 30, 50].map((level) => (
                  <button
                    key={level}
                    onClick={() => setTargetLevel(level)}
                    className="py-2 rounded-xl bg-gray-100 dark:bg-white/10 hover:bg-gray-200 dark:hover:bg-white/20 text-gray-600 dark:text-gray-300 text-xs font-bold cursor-pointer transition-colors"
                  >
                    Level {level}
                  </button>
                ))}
              </div>
            </div>
          </Squircle>

          <div className="lg:col-span-2 space-y-6">
            <Squircle
              cornerRadius={32}
              className="p-6 bg-paper-2 shadow-sm animate-in"
              style={{ animationDelay: "0.15s" }}
            >
              <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4 mb-6">
                <div>
                  <span className="text-sm font-bold text-gray-500 dark:text-gray-400">
                    Needed XP
                  </span>
                  <p className="mt-2 text-2xl lg:text-3xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(neededXp)} XP
                  </p>
                </div>
                <button
                  onClick={copySummary}
                  className="inline-flex items-center justify-center gap-2 rounded-xl bg-orange-500 hover:bg-orange-600 text-white font-bold px-4 py-2.5 text-sm transition-colors cursor-pointer active:scale-95"
                >
                  {copied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                  {copied ? "Copied" : "Copy"}
                </button>
              </div>

              <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
                <div className="rounded-2xl bg-paper p-4">
                  <Target className="w-5 h-5 text-orange-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(levelsNeeded)}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Levels</p>
                </div>
                <div className="rounded-2xl bg-paper p-4">
                  <FlaskConical className="w-5 h-5 text-green-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(bottlesAverage)}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Bottles Avg</p>
                </div>
                <div className="rounded-2xl bg-paper p-4">
                  <Sparkles className="w-5 h-5 text-blue-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(xpToNextLevel(currentLevel))}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Next Level</p>
                </div>
                <div className="rounded-2xl bg-paper p-4">
                  <BarChart3 className="w-5 h-5 text-purple-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(Math.round(targetXp))}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Target Total</p>
                </div>
              </div>
            </Squircle>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <Squircle
                cornerRadius={32}
                className="p-6 bg-paper-2 shadow-sm animate-in"
                style={{ animationDelay: "0.2s" }}
              >
                <div className="flex items-center gap-2 mb-4">
                  <div className="p-1.5 rounded-lg bg-orange-500/10">
                    <Target className="w-4 h-4 text-orange-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Level Path
                  </h2>
                </div>
                <div className="h-5 rounded-full bg-paper overflow-hidden">
                  <div
                    className="h-full bg-orange-500 transition-all"
                    style={{ width: `${pathPercent}%` }}
                  />
                </div>
                <div className="mt-3 flex items-center justify-between text-xs font-bold text-gray-500 dark:text-gray-400">
                  <span>Level {currentLevel}</span>
                  <span>Level {targetLevel}</span>
                </div>
              </Squircle>

              <Squircle
                cornerRadius={32}
                className="p-6 bg-paper-2 shadow-sm animate-in"
                style={{ animationDelay: "0.25s" }}
              >
                <div className="flex items-center gap-2 mb-4">
                  <div className="p-1.5 rounded-lg bg-blue-500/10">
                    <FlaskConical className="w-4 h-4 text-blue-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Bottle Range
                  </h2>
                </div>
                <div className="space-y-3 text-sm text-gray-600 dark:text-gray-400">
                  <div className="flex items-center justify-between gap-4">
                    <span>Best case</span>
                    <span className="font-bold text-gray-800 dark:text-gray-100">
                      {formatAmount(bottlesBest)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span>Average</span>
                    <span className="font-bold text-gray-800 dark:text-gray-100">
                      {formatAmount(bottlesAverage)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span>Worst case</span>
                    <span className="font-bold text-gray-800 dark:text-gray-100">
                      {formatAmount(bottlesWorst)}
                    </span>
                  </div>
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
