"use client";

import { useMemo } from "react";
import { Fireworks } from "@fireworks-js/react";
import Squircle from "@/components/Squircle";
import advancementRegistry from "@/data/advancements.json";

interface AdvancementData {
  completed: boolean;
  completed_at: number | null;
}

interface PlayerAdvancementsProps {
  advancements: Record<string, AdvancementData>;
  total: number;
  completed: number;
}

interface RegistryEntry {
  id: string;
  name: string;
  description: string;
  category: string;
  icon: string;
}


const CATEGORY_ORDER = ["story", "nether", "end", "adventure", "husbandry"];
const CATEGORY_LABELS: Record<string, string> = {
  story: "Story",
  nether: "Nether",
  end: "End",
  adventure: "Adventure",
  husbandry: "Husbandry",
};

export default function PlayerAdvancements({
  advancements,
  total,
  completed,
}: PlayerAdvancementsProps) {
  const percentage = total > 0 ? ((completed / total) * 100).toFixed(1) : "0";
  const isFullCompletion = total > 0 && completed === total;

  const grouped = useMemo(() => {
    const registry = advancementRegistry as RegistryEntry[];
    const groups: Record<
      string,
      { done: { id: string; name: string; description: string; icon: string }[]; notDone: { id: string; name: string; description: string; icon: string }[]; total: number; completed: number }
    > = {};

    for (const cat of CATEGORY_ORDER) {
      groups[cat] = { done: [], notDone: [], total: 0, completed: 0 };
    }

    for (const entry of registry) {
      const cat = entry.category;
      if (!groups[cat]) continue;

      const advData = advancements[entry.id];
      const isDone = advData?.completed ?? false;

      groups[cat].total++;
      if (isDone) {
        groups[cat].completed++;
        groups[cat].done.push({ id: entry.id, name: entry.name, description: entry.description, icon: entry.icon });
      } else {
        groups[cat].notDone.push({ id: entry.id, name: entry.name, description: entry.description, icon: entry.icon });
      }
    }

    return groups;
  }, [advancements]);

  return (
    <div className="flex flex-col gap-3 animate-in" style={{ animationDelay: "0.3s" }}>
      <p className="text-[11px] uppercase tracking-wider text-gray-500 dark:text-gray-500 pl-1">
        Advancements
      </p>

      {/* Progress summary card */}
      <div className="relative">
        {isFullCompletion && (
          <Fireworks
            className="absolute -inset-12 z-20 pointer-events-none"
            options={{
              rocketsPoint: { min: 30, max: 70 },
              particles: 30,
              intensity: 12,
              explosion: 2,
              traceLength: 2,
              traceSpeed: 8,
              flickering: 30,
              opacity: 0.6,
              brightness: { min: 50, max: 80 },
              decay: { min: 0.02, max: 0.04 },
              mouse: { click: false, move: false, max: 1 },
              colors: ["#ff6b35", "#ffd700", "#ff4081", "#00e5ff", "#76ff03", "#e040fb"],
            }}
          />
        )}
        <Squircle
          cornerRadius={16}
          className={`px-5 py-3 relative ${
            isFullCompletion
              ? "bg-gradient-to-br from-[#F59E0B] to-[#FBBF24]"
              : "bg-gradient-to-br from-[#F97316] to-[#FB923C]"
          }`}
        >
          <span className="absolute top-1/2 right-4 -translate-y-1/2 text-[48px] font-bold text-white/20 select-none pointer-events-none font-mc">
            {percentage}%
          </span>
          <div className="relative z-10">
            <p className="text-white/70 text-[10px] uppercase tracking-wider mb-1">Progress</p>
            <p className="text-white text-2xl font-bold font-mc">
              {completed}<span className="text-white/50 text-base">/{total}</span>
            </p>
            <div className="h-[6px] bg-black/20 rounded-full overflow-hidden mt-3">
              <div
                className="h-full bg-white rounded-full transition-all duration-500"
                style={{ width: `${percentage}%` }}
              />
            </div>
          </div>
        </Squircle>
      </div>

      {/* Advancement list */}
      <Squircle
        cornerRadius={16}
        className="bg-paper-2 max-h-[600px] overflow-y-auto themed-scrollbar"
      >
        {CATEGORY_ORDER.map((cat) => {
          const group = grouped[cat];
          if (!group || group.total === 0) return null;

          const isComplete = group.completed === group.total;
          const items = [...group.done, ...group.notDone];

          return (
            <div key={cat}>
              {/* Category header */}
              <div
                className="flex justify-between items-center px-3 py-2 border-b border-line sticky top-0 z-10 backdrop-blur-md bg-orange-500/5"
              >
                <span className="text-[11px] font-semibold text-orange-500">
                  {CATEGORY_LABELS[cat]}
                </span>
                <span className="text-[11px] text-orange-500">
                  {group.completed}/{group.total}
                </span>
              </div>

              {/* Advancement rows */}
              {items.map((item, i) => {
                const isDone = advancements[item.id]?.completed ?? false;

                return (
                  <div
                    key={item.id}
                    className={`flex items-center gap-2 px-3 py-1.5 border-b border-line/25 ${
                      i % 2 === 1 ? "bg-paper/30 dark:bg-[#1a1412]/30" : ""
                    } ${isDone ? "bg-green-400/[0.02]" : "opacity-45"}`}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={item.icon}
                      alt=""
                      width={32}
                      height={32}
                      className="flex-shrink-0 pixelated rounded"
                    />
                    <div className="flex-1 min-w-0">
                      <p
                        className={`text-[12px] font-medium truncate ${
                          isDone
                            ? "text-foreground"
                            : "text-gray-500 dark:text-[#8a7e72]"
                        }`}
                      >
                        {item.name}
                      </p>
                      <p className="text-[10px] text-gray-400 dark:text-[#5a504a] truncate">
                        {item.description}
                      </p>
                    </div>
                    <div
                      className={`w-5 h-5 rounded-full flex-shrink-0 flex items-center justify-center ${
                        isDone
                          ? "bg-green-400/15"
                          : "bg-white/5"
                      }`}
                    >
                      {isDone && (
                        <span className="text-green-400 text-[11px] font-bold">&#10003;</span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          );
        })}
      </Squircle>
    </div>
  );
}
