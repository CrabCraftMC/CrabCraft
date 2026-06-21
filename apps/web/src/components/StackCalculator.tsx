"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Squircle from "@/components/Squircle";
import {
  Boxes,
  Check,
  Copy,
  Info,
  Package,
  RotateCcw,
  Users,
} from "lucide-react";

const STORAGE_KEY = "crabcraft-stack-calculator";
const MAX_ITEMS = 999_999_999;

const DEFAULTS = {
  totalItems: 3_456,
  stackSize: 64,
  builders: 1,
};

const STACK_OPTIONS = [
  { value: 64, label: "64", hint: "Most items" },
  { value: 16, label: "16", hint: "Signs, eggs" },
  { value: 1, label: "1", hint: "Tools" },
];

const INPUT_CLS =
  "w-full text-sm font-bold bg-transparent text-gray-700 dark:text-gray-200 outline-none";
const INPUT_WRAP =
  "flex items-center bg-gray-100 dark:bg-white/10 rounded-lg px-3 py-2 focus-within:ring-2 focus-within:ring-orange-500/50";

type Breakdown = {
  shulkers: number;
  stacks: number;
  items: number;
  slots: number;
  shulkersNeeded: number;
  doubleChestsNeeded: number;
  inventoryLoads: number;
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

function sanitizeCount(raw: string) {
  const digits = raw.replace(/\D/g, "").slice(0, 9);
  if (!digits) return "";
  return String(Math.min(MAX_ITEMS, Number(digits)));
}

function formatAmount(value: number) {
  return new Intl.NumberFormat("en-US").format(value);
}

function formatUnit(value: number, singular: string, plural = `${singular}s`) {
  return `${formatAmount(value)} ${value === 1 ? singular : plural}`;
}

function getBreakdown(totalItems: number, stackSize: number): Breakdown {
  const itemsPerShulker = stackSize * 27;
  const slots = totalItems === 0 ? 0 : Math.ceil(totalItems / stackSize);
  const shulkers = Math.floor(totalItems / itemsPerShulker);
  const afterShulkers = totalItems % itemsPerShulker;
  const stacks = Math.floor(afterShulkers / stackSize);
  const items = afterShulkers % stackSize;

  return {
    shulkers,
    stacks,
    items,
    slots,
    shulkersNeeded: slots === 0 ? 0 : Math.ceil(slots / 27),
    doubleChestsNeeded: slots === 0 ? 0 : Math.ceil(slots / 54),
    inventoryLoads: slots === 0 ? 0 : Math.ceil(slots / 36),
  };
}

function breakdownText(breakdown: Breakdown) {
  const parts = [
    breakdown.shulkers > 0
      ? formatUnit(breakdown.shulkers, "shulker box", "shulker boxes")
      : null,
    breakdown.stacks > 0 ? formatUnit(breakdown.stacks, "stack") : null,
    breakdown.items > 0 ? formatUnit(breakdown.items, "item") : null,
  ].filter(Boolean);

  return parts.length > 0 ? parts.join(", ") : "0 items";
}

export default function StackCalculator() {
  const [totalInput, setTotalInput] = useState(String(DEFAULTS.totalItems));
  const [stackSize, setStackSize] = useState(DEFAULTS.stackSize);
  const [builders, setBuilders] = useState(DEFAULTS.builders);
  const [hydrated, setHydrated] = useState(false);
  const [copied, setCopied] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);

  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      if (saved.totalItems !== undefined) {
        setTotalInput(sanitizeCount(String(saved.totalItems)) || "0");
      }
      if (saved.stackSize && [1, 16, 64].includes(saved.stackSize)) {
        setStackSize(saved.stackSize);
      }
      if (saved.builders) {
        setBuilders(Math.max(1, Math.min(12, Number(saved.builders) || 1)));
      }
    }
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        totalItems: Number(totalInput || 0),
        stackSize,
        builders,
      })
    );
  }, [totalInput, stackSize, builders, hydrated]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && showResetConfirm) setShowResetConfirm(false);
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [showResetConfirm]);

  const totalItems = Number(totalInput || 0);

  const breakdown = useMemo(
    () => getBreakdown(totalItems, stackSize),
    [totalItems, stackSize]
  );
  const perBuilderItems = Math.ceil(totalItems / builders);
  const perBuilderBreakdown = useMemo(
    () => getBreakdown(perBuilderItems, stackSize),
    [perBuilderItems, stackSize]
  );

  const partialSlots = useMemo(() => {
    if (breakdown.shulkersNeeded === 0) return 0;
    return breakdown.slots % 27 || 27;
  }, [breakdown.shulkersNeeded, breakdown.slots]);

  const partialSlotIndex = totalItems % stackSize === 0 ? -1 : partialSlots - 1;

  const setAmount = useCallback((value: number) => {
    setTotalInput(String(Math.max(0, Math.min(MAX_ITEMS, value))));
  }, []);

  const resetAll = useCallback(() => {
    setTotalInput(String(DEFAULTS.totalItems));
    setStackSize(DEFAULTS.stackSize);
    setBuilders(DEFAULTS.builders);
  }, []);

  const copySummary = useCallback(() => {
    const splitText =
      builders > 1
        ? ` Split ${builders} ways: ${formatAmount(perBuilderItems)} each (${breakdownText(perBuilderBreakdown)}).`
        : "";
    navigator.clipboard.writeText(
      `${formatAmount(totalItems)} items at ${stackSize} per stack: ${breakdownText(breakdown)}.${splitText}`
    );
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }, [
    breakdown,
    builders,
    perBuilderBreakdown,
    perBuilderItems,
    stackSize,
    totalItems,
  ]);

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-5xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Stack & Shulker
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Convert item counts into stacks, shulkers, and storage space
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Squircle
            cornerRadius={32}
            className="p-6 bg-paper-2 shadow-sm animate-in lg:col-span-1"
            style={{ animationDelay: "0.1s" }}
          >
            <div className="flex items-center justify-between mb-4">
              <label className="text-sm font-bold text-gray-700 dark:text-gray-300">
                Materials
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
                <span className="block text-sm font-bold text-gray-600 dark:text-gray-400 mb-1.5">
                  Total Items
                </span>
                <div className={INPUT_WRAP}>
                  <input
                    type="text"
                    inputMode="numeric"
                    value={totalInput}
                    onChange={(e) => setTotalInput(sanitizeCount(e.target.value))}
                    className={INPUT_CLS}
                  />
                </div>
                <div className="grid grid-cols-2 gap-2 mt-2">
                  <button
                    onClick={() => setAmount(totalItems + stackSize)}
                    className="py-2 rounded-xl bg-gray-100 dark:bg-white/10 hover:bg-gray-200 dark:hover:bg-white/20 text-gray-600 dark:text-gray-300 text-xs font-bold cursor-pointer transition-colors"
                  >
                    + Stack
                  </button>
                  <button
                    onClick={() => setAmount(totalItems + stackSize * 27)}
                    className="py-2 rounded-xl bg-gray-100 dark:bg-white/10 hover:bg-gray-200 dark:hover:bg-white/20 text-gray-600 dark:text-gray-300 text-xs font-bold cursor-pointer transition-colors"
                  >
                    + Shulker
                  </button>
                </div>
              </div>

              <div>
                <span className="block text-sm font-bold text-gray-600 dark:text-gray-400 mb-1.5">
                  Stack Size
                </span>
                <div className="grid grid-cols-3 gap-1">
                  {STACK_OPTIONS.map((option) => (
                    <button
                      key={option.value}
                      onClick={() => setStackSize(option.value)}
                      className={`min-h-16 rounded-xl px-2 py-2 text-center cursor-pointer transition-colors ${
                        stackSize === option.value
                          ? "bg-orange-500 text-white"
                          : "bg-gray-100 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/20"
                      }`}
                    >
                      <span className="block text-lg font-bold leading-tight">
                        {option.label}
                      </span>
                      <span className="block text-[10px] font-bold opacity-70">
                        {option.hint}
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-sm font-bold text-gray-600 dark:text-gray-400">
                    Builders
                  </span>
                  <input
                    type="number"
                    min={1}
                    max={12}
                    value={builders}
                    onChange={(e) =>
                      setBuilders(Math.max(1, Math.min(12, Number(e.target.value) || 1)))
                    }
                    className="w-16 text-right text-sm font-bold bg-gray-100 dark:bg-white/10 rounded-lg px-2 py-1 text-gray-700 dark:text-gray-200 outline-none"
                  />
                </div>
                <input
                  type="range"
                  min={1}
                  max={12}
                  value={builders}
                  onChange={(e) => setBuilders(Number(e.target.value))}
                  className="w-full cursor-pointer"
                />
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
                    Breakdown
                  </span>
                  <p className="mt-2 text-2xl lg:text-3xl font-bold text-gray-800 dark:text-gray-100">
                    {breakdownText(breakdown)}
                  </p>
                </div>
                <button
                  onClick={copySummary}
                  className="inline-flex items-center justify-center gap-2 rounded-xl bg-orange-500 hover:bg-orange-600 text-white font-bold px-4 py-2.5 text-sm transition-colors cursor-pointer active:scale-95"
                >
                  {copied ? (
                    <Check className="w-4 h-4" />
                  ) : (
                    <Copy className="w-4 h-4" />
                  )}
                  {copied ? "Copied" : "Copy"}
                </button>
              </div>

              <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
                <div className="rounded-2xl bg-paper p-4">
                  <Package className="w-5 h-5 text-orange-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(breakdown.slots)}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Slots</p>
                </div>
                <div className="rounded-2xl bg-paper p-4">
                  <Boxes className="w-5 h-5 text-green-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(breakdown.shulkersNeeded)}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Shulkers</p>
                </div>
                <div className="rounded-2xl bg-paper p-4">
                  <Package className="w-5 h-5 text-blue-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(breakdown.doubleChestsNeeded)}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Double Chests</p>
                </div>
                <div className="rounded-2xl bg-paper p-4">
                  <Users className="w-5 h-5 text-purple-500 mb-2" />
                  <p className="text-xl font-bold text-gray-800 dark:text-gray-100">
                    {formatAmount(perBuilderItems)}
                  </p>
                  <p className="text-xs font-bold text-gray-400">Each</p>
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
                    <Boxes className="w-4 h-4 text-orange-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Shulker Fill
                  </h2>
                </div>
                <div className="grid grid-cols-9 gap-1">
                  {Array.from({ length: 27 }, (_, index) => {
                    const filled = index < partialSlots;
                    const partial = index === partialSlotIndex;
                    return (
                      <div
                        key={index}
                        className={`aspect-square rounded-[4px] border ${
                          filled
                            ? partial
                              ? "bg-amber-400 border-amber-500"
                              : "bg-orange-500 border-orange-600"
                            : "bg-paper border-line"
                        }`}
                      />
                    );
                  })}
                </div>
                <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
                  {breakdown.shulkersNeeded === 0
                    ? "No shulker needed"
                    : `${partialSlots} of 27 slots used in the shown shulker`}
                </p>
              </Squircle>

              <Squircle
                cornerRadius={32}
                className="p-6 bg-paper-2 shadow-sm animate-in"
                style={{ animationDelay: "0.25s" }}
              >
                <div className="flex items-center gap-2 mb-4">
                  <div className="p-1.5 rounded-lg bg-blue-500/10">
                    <Info className="w-4 h-4 text-blue-500" />
                  </div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Carry Plan
                  </h2>
                </div>
                <div className="space-y-3 text-sm text-gray-600 dark:text-gray-400">
                  <div className="flex items-center justify-between gap-4">
                    <span>Inventory trips</span>
                    <span className="font-bold text-gray-800 dark:text-gray-100">
                      {formatAmount(breakdown.inventoryLoads)}
                    </span>
                  </div>
                  <div className="flex items-start justify-between gap-4">
                    <span>Split per builder</span>
                    <span className="text-right font-bold text-gray-800 dark:text-gray-100">
                      {breakdownText(perBuilderBreakdown)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span>Items per shulker</span>
                    <span className="font-bold text-gray-800 dark:text-gray-100">
                      {formatAmount(stackSize * 27)}
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
