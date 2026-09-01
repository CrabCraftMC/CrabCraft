"use client";

import {
  useState,
  useEffect,
  useCallback,
  type ClipboardEvent,
} from "react";
import Squircle from "@/components/Squircle";
import { parsePortalCoordinates } from "@/data/portal-coordinates";
import { RotateCcw, ArrowLeftRight, Info, AlertTriangle, Link2 } from "lucide-react";
import { trackUmamiEventOnce } from "@/lib/umami";

const STORAGE_KEY = "crabcraft-nether-portal";

const DEFAULTS = {
  overworldX: 0,
  overworldZ: 0,
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

/** Strip non-digits/minus, then: odd minus count → negative, even → positive */
function sanitize(raw: string): string {
  const stripped = raw.replace(/[^0-9-]/g, "");
  const minusCount = (stripped.match(/-/g) || []).length;
  const digits = stripped.replace(/-/g, "");
  if (!digits) return minusCount % 2 === 1 ? "-" : "";
  return (minusCount % 2 === 1 ? "-" : "") + digits;
}

function toNum(s: string): number {
  if (s === "" || s === "-") return 0;
  const n = parseInt(s, 10);
  return isNaN(n) ? 0 : n;
}

const INPUT_WRAP =
  "flex items-center bg-gray-100 dark:bg-white/10 rounded-lg px-3 py-2 focus-within:ring-2 focus-within:ring-orange-500/50";
const INPUT_LABEL =
  "text-sm font-bold text-gray-400 dark:text-gray-500 mr-2 select-none";
const INPUT_CLS =
  "w-full text-sm font-bold bg-transparent text-gray-700 dark:text-gray-200 outline-none";

export default function NetherPortalCalculator() {
  const [overworldX, setOverworldX] = useState(DEFAULTS.overworldX);
  const [overworldZ, setOverworldZ] = useState(DEFAULTS.overworldZ);
  const [hydrated, setHydrated] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);

  // Raw strings for the actively-edited field so "-" doesn't get eaten
  const [editingField, setEditingField] = useState<string | null>(null);
  const [editingValue, setEditingValue] = useState("");

  // Load saved state
  useEffect(() => {
    const saved = loadSaved();
    if (saved) {
      if (saved.overworldX !== undefined) setOverworldX(saved.overworldX);
      if (saved.overworldZ !== undefined) setOverworldZ(saved.overworldZ);
    }
    setHydrated(true);
  }, []);

  // Persist state
  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ overworldX, overworldZ })
    );
  }, [overworldX, overworldZ, hydrated]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && showResetConfirm) setShowResetConfirm(false);
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [showResetConfirm]);

  const resetAll = useCallback(() => {
    setOverworldX(DEFAULTS.overworldX);
    setOverworldZ(DEFAULTS.overworldZ);
    setEditingField(null);
  }, []);

  // Derived nether coords
  const netherX = Math.floor(overworldX / 8);
  const netherZ = Math.floor(overworldZ / 8);

  const handleChange = (
    field: string,
    raw: string,
    apply: (n: number) => void
  ) => {
    trackUmamiEventOnce("portal-calculator", "tool-used", {
      tool: "portal-calculator",
      action: "edit-coordinates",
    });
    const clean = sanitize(raw);
    setEditingField(field);
    setEditingValue(clean);
    apply(toNum(clean));
  };

  const handleBlur = () => {
    setEditingField(null);
  };

  const handleCoordinatePaste = (
    event: ClipboardEvent<HTMLInputElement>,
    dimension: "overworld" | "nether",
  ) => {
    const coordinates = parsePortalCoordinates(
      event.clipboardData.getData("text"),
    );
    if (!coordinates) return;

    event.preventDefault();
    trackUmamiEventOnce("portal-calculator", "tool-used", {
      tool: "portal-calculator",
      action: "paste-coordinates",
    });
    setEditingField(null);

    const scale = dimension === "nether" ? 8 : 1;
    setOverworldX(coordinates.x * scale);
    setOverworldZ(coordinates.z * scale);
  };

  const inputValue = (field: string, numericValue: number) =>
    editingField === field ? editingValue : String(numericValue);

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-4xl">
        {/* Header */}
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Portal Coordinates
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Convert coordinates between the Overworld and Nether
          </p>
        </div>

        {/* Converter */}
        <Squircle
          cornerRadius={32}
          className="p-6 bg-paper-2 shadow-sm animate-in mb-6"
          style={{ animationDelay: "0.1s" }}
        >
          <div className="flex items-center justify-between mb-6">
            <label className="text-sm font-bold text-gray-700 dark:text-gray-300">
              Coordinates
            </label>
            <button
              onClick={() => setShowResetConfirm(true)}
              aria-label="Reset all coordinates"
              className="flex items-center gap-1.5 text-xs font-bold text-gray-400 hover:text-orange-500 transition-colors cursor-pointer"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              Reset
            </button>
          </div>

          <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-4">
            {/* Overworld */}
            <div className="flex-1">
              <div className="text-center mb-3">
                <span className="inline-block px-3 py-1 rounded-lg bg-green-500/10 text-green-600 dark:text-green-400 text-sm font-bold">
                  Overworld
                </span>
              </div>
              <div className="space-y-3">
                <div className={INPUT_WRAP}>
                  <span className={INPUT_LABEL}>X</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    value={inputValue("ow-x", overworldX)}
                    onChange={(e) =>
                      handleChange("ow-x", e.target.value, setOverworldX)
                    }
                    onPaste={(e) => handleCoordinatePaste(e, "overworld")}
                    onBlur={handleBlur}
                    className={INPUT_CLS}
                  />
                </div>
                <div className={INPUT_WRAP}>
                  <span className={INPUT_LABEL}>Z</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    value={inputValue("ow-z", overworldZ)}
                    onChange={(e) =>
                      handleChange("ow-z", e.target.value, setOverworldZ)
                    }
                    onPaste={(e) => handleCoordinatePaste(e, "overworld")}
                    onBlur={handleBlur}
                    className={INPUT_CLS}
                  />
                </div>
              </div>
            </div>

            {/* Arrow */}
            <div className="hidden sm:flex items-center justify-center mt-8">
              <div className="p-2 rounded-full bg-orange-500/10">
                <ArrowLeftRight className="w-5 h-5 text-orange-500" />
              </div>
            </div>
            <div className="flex sm:hidden items-center justify-center">
              <div className="p-2 rounded-full bg-orange-500/10">
                <ArrowLeftRight className="w-5 h-5 text-orange-500 rotate-90" />
              </div>
            </div>

            {/* Nether */}
            <div className="flex-1">
              <div className="text-center mb-3">
                <span className="inline-block px-3 py-1 rounded-lg bg-red-500/10 text-red-500 dark:text-red-400 text-sm font-bold">
                  Nether
                </span>
              </div>
              <div className="space-y-3">
                <div className={INPUT_WRAP}>
                  <span className={INPUT_LABEL}>X</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    value={inputValue("n-x", netherX)}
                    onChange={(e) =>
                      handleChange("n-x", e.target.value, (n) =>
                        setOverworldX(n * 8)
                      )
                    }
                    onPaste={(e) => handleCoordinatePaste(e, "nether")}
                    onBlur={handleBlur}
                    className={INPUT_CLS}
                  />
                </div>
                <div className={INPUT_WRAP}>
                  <span className={INPUT_LABEL}>Z</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    value={inputValue("n-z", netherZ)}
                    onChange={(e) =>
                      handleChange("n-z", e.target.value, (n) =>
                        setOverworldZ(n * 8)
                      )
                    }
                    onPaste={(e) => handleCoordinatePaste(e, "nether")}
                    onBlur={handleBlur}
                    className={INPUT_CLS}
                  />
                </div>
              </div>
            </div>
          </div>

          {/* Ratio hint */}
          <p className="mt-4 text-center text-xs text-gray-400 dark:text-gray-500">
            1 Nether block = 8 Overworld blocks
          </p>
        </Squircle>

        {/* Info Cards */}
        <div className="grid gap-4 grid-cols-1 sm:grid-cols-2">
          <Squircle
            cornerRadius={24}
            className="p-5 bg-paper-2 shadow-sm animate-in sm:col-span-2"
            style={{ animationDelay: "0.15s" }}
          >
            <div className="flex items-center gap-2 mb-3">
              <div className="p-1.5 rounded-lg bg-blue-500/10">
                <Info className="w-4 h-4 text-blue-500" />
              </div>
              <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                How Portal Linking Works
              </h2>
            </div>
            <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed">
              Divide your Overworld X and Z by 8 to get the matching Nether
              spot. <strong>Height (Y) stays the same</strong> and is never
              divided. When you step through a portal, the game looks for the
              nearest existing portal nearby. It searches{" "}
              <strong>16 blocks</strong> around you in the Nether, or{" "}
              <strong>128 blocks</strong> in the Overworld.
            </p>
          </Squircle>

          <Squircle
            cornerRadius={24}
            className="p-5 bg-paper-2 shadow-sm animate-in"
            style={{ animationDelay: "0.2s" }}
          >
            <div className="flex items-center gap-2 mb-3">
              <div className="p-1.5 rounded-lg bg-green-500/10">
                <Link2 className="w-4 h-4 text-green-500" />
              </div>
              <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                Forced Linking
              </h2>
            </div>
            <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed">
              Build your Nether-side portal at the exact coordinates this
              calculator gives you. If you&apos;re even a few blocks off, the
              game might connect you to a completely different portal instead.
            </p>
          </Squircle>

          <Squircle
            cornerRadius={24}
            className="p-5 bg-paper-2 shadow-sm animate-in"
            style={{ animationDelay: "0.25s" }}
          >
            <div className="flex items-center gap-2 mb-3">
              <div className="p-1.5 rounded-lg bg-amber-500/10">
                <AlertTriangle className="w-4 h-4 text-amber-500" />
              </div>
              <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                Common Mistakes
              </h2>
            </div>
            <ul className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed space-y-1.5">
              <li className="flex gap-2">
                <span className="text-gray-400 select-none">&bull;</span>
                Placing Overworld portals too close together, they&apos;ll end up sharing the same Nether portal
              </li>
<li className="flex gap-2">
                <span className="text-gray-400 select-none">&bull;</span>
                Forgetting that height matters, portals at different Y levels can link to completely different places
              </li>
            </ul>
          </Squircle>
        </div>
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
