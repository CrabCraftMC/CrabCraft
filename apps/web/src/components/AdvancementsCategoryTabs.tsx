"use client";

import { useRouter } from "next/navigation";
import Squircle from "@/components/Squircle";
import {
  CATEGORY_ORDER,
  CATEGORY_LABELS,
  type AdvancementCategory,
} from "@/lib/advancementCategories";

interface AdvancementsCategoryTabsProps {
  active: AdvancementCategory | null;
}

export default function AdvancementsCategoryTabs({
  active,
}: AdvancementsCategoryTabsProps) {
  const router = useRouter();

  const go = (cat: AdvancementCategory | null) => {
    router.push(
      cat
        ? `/leaderboard/advancements?category=${cat}`
        : "/leaderboard/advancements",
    );
  };

  return (
    <div
      className="flex gap-2 pb-2 mb-6 justify-center flex-wrap animate-in"
      style={{ animationDelay: "0.05s" }}
    >
      <Squircle
        cornerRadius={14}
        onClick={() => go(null)}
        className={`px-4 py-2 text-sm font-bold whitespace-nowrap transition-colors cursor-pointer ${
          active === null
            ? "bg-orange-500 text-white"
            : "bg-gray-200 dark:bg-[#2a221b] text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-[#3d3028]"
        }`}
      >
        All
      </Squircle>
      {CATEGORY_ORDER.map((cat) => (
        <Squircle
          key={cat}
          cornerRadius={14}
          onClick={() => go(cat)}
          className={`px-4 py-2 text-sm font-bold whitespace-nowrap transition-colors cursor-pointer ${
            active === cat
              ? "bg-orange-500 text-white"
              : "bg-gray-200 dark:bg-[#2a221b] text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-[#3d3028]"
          }`}
        >
          {CATEGORY_LABELS[cat]}
        </Squircle>
      ))}
    </div>
  );
}
