"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { useWebHaptics } from "web-haptics/react";
import { useRouter } from "next/navigation";
import {
  Home, BarChart3, Trophy, Palette, Rainbow, Circle, ArrowLeftRight, BookOpen,
  Boxes, Sparkles, Search, ExternalLink, ImageIcon,
} from "lucide-react";
import { FaDiscord, FaTiktok, FaYoutube } from "react-icons/fa";
import { Instagram } from "lucide-react";
import PixelIcon from "@/components/PixelIcon";
import config from "@/data/site-config.json";
import { playerDisplayName } from "@/lib/playerName";
import { trackUmamiEvent } from "@/lib/umami";

const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  Home, BarChart3, Trophy, Palette, Rainbow, Circle, ArrowLeftRight, BookOpen,
  Boxes, Sparkles, ImageIcon, Search,
  youtube: FaYoutube, instagram: Instagram, tiktok: FaTiktok, discord: FaDiscord,
};

type Result = {
  id: string;
  label: string;
  icon?: string;
  url: string;
  external?: boolean;
  category: "Pages" | "Games" | "Tools" | "Awards" | "Players" | "Socials";
  avatar?: string;
  searchText?: string;
};

const PAGES: Result[] = config.navbar.links.map((l) => ({
  id: `page-${l.url}`,
  label: l.name,
  icon: l.icon,
  url: l.url,
  category: "Pages",
}));

const TOOLS: Result[] = config.navbar.tools.map((t) => ({
  id: `tool-${t.url}`,
  label: t.name,
  icon: t.icon,
  url: t.url,
  category: "Tools",
}));

const GAMES: Result[] = config.navbar.games.map((game) => ({
  id: `game-${game.url}`,
  label: game.name,
  avatar: game.icon,
  url: game.url,
  category: "Games",
}));

const socialNames: Record<string, string> = {
  youtube: "YouTube",
  tiktok: "TikTok",
  instagram: "Instagram",
  discord: "Discord",
};

const SOCIALS: Result[] = config.navbar.socials.map((s) => ({
  id: `social-${s.platform}`,
  label: socialNames[s.platform] || s.platform,
  icon: s.platform,
  url: s.url,
  external: true,
  category: "Socials",
}));

const STATIC_ITEMS = [...PAGES, ...GAMES, ...TOOLS, ...SOCIALS];

export default function CommandMenu() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const [players, setPlayers] = useState<Result[]>([]);
  const [awards, setAwards] = useState<Result[] | null>(null);
  const [searching, setSearching] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const router = useRouter();
  const { trigger } = useWebHaptics();

  // Filter static items
  const q = query.toLowerCase().trim();
  const filteredStatic = q
    ? STATIC_ITEMS.filter((item) => item.label.toLowerCase().includes(q))
    : STATIC_ITEMS;
  const filteredAwards = q
    ? (awards ?? []).filter((award) => award.searchText?.includes(q))
    : [];

  const results = [...filteredStatic, ...filteredAwards, ...players];

  // Group results by category
  const grouped = results.reduce<Record<string, Result[]>>((acc, r) => {
    (acc[r.category] ??= []).push(r);
    return acc;
  }, {});
  const categoryOrder = ["Pages", "Games", "Tools", "Awards", "Players", "Socials"] as const;
  const flatResults = categoryOrder.flatMap((cat) => grouped[cat] || []);

  // Load the small award catalogue once, when search is first opened.
  useEffect(() => {
    if (!open || awards !== null) return;

    let cancelled = false;
    fetch("/api/awards")
      .then((response) => (response.ok ? response.json() : []))
      .then(
        (
          data: Array<{
            id: string;
            title: string;
            description: string;
            icon: string;
          }>,
        ) => {
          if (cancelled) return;
          setAwards(
            data.map((award) => ({
              id: `award-${award.id}`,
              label: award.title,
              url: `/awards/${award.id}`,
              category: "Awards" as const,
              avatar: award.icon,
              searchText: `${award.title} ${award.description} ${award.id}`.toLocaleLowerCase("en-GB"),
            })),
          );
        },
      )
      .catch(() => {
        if (!cancelled) setAwards([]);
      });

    return () => {
      cancelled = true;
    };
  }, [awards, open]);

  // Debounced player search
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (q.length < 2) {
      setPlayers([]);
      setSearching(false);
      return;
    }
    setSearching(true);
    debounceRef.current = setTimeout(async () => {
      try {
        const res = await fetch(`/api/players/search?q=${encodeURIComponent(q)}`);
        const data = await res.json();
        setPlayers(
          data.map((p: { minecraft_uuid: string; minecraft_username: string; nickname: string | null }) => ({
            id: `player-${p.minecraft_uuid}`,
            label: playerDisplayName(p.nickname, p.minecraft_username),
            url: `/stats/${p.minecraft_uuid}`,
            category: "Players" as const,
            avatar: `https://mc-heads.net/avatar/${p.minecraft_uuid}/24.png`,
          }))
        );
      } catch {
        setPlayers([]);
      }
      setSearching(false);
    }, 250);
  }, [q]);

  // Reset state on open/close
  useEffect(() => {
    if (open) {
      setQuery("");
      setPlayers([]);
      setActiveIndex(0);
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  // Clamp active index when results change
  useEffect(() => {
    setActiveIndex((prev) => Math.min(prev, Math.max(flatResults.length - 1, 0)));
  }, [flatResults.length]);

  // Scroll active item into view
  useEffect(() => {
    const el = listRef.current?.querySelector(`[data-index="${activeIndex}"]`);
    el?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  // Global keyboard shortcut + custom event
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
      if (e.key === "Escape" && open) {
        e.preventDefault();
        setOpen(false);
      }
    };
    const handleOpen = () => setOpen(true);
    window.addEventListener("keydown", handleKeyDown);
    window.addEventListener("open-command-menu", handleOpen);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("open-command-menu", handleOpen);
    };
  }, [open]);

  const navigate = useCallback(
    (result: Result) => {
      trigger();
      trackUmamiEvent("site-search-result-selected", {
        category: result.category,
      });
      setOpen(false);
      if (result.external) {
        window.open(result.url, "_blank", "noopener,noreferrer");
      } else {
        router.push(result.url);
      }
    },
    [router, trigger]
  );

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActiveIndex((prev) => (prev + 1) % flatResults.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIndex((prev) => (prev - 1 + flatResults.length) % flatResults.length);
    } else if (e.key === "Enter" && flatResults[activeIndex]) {
      e.preventDefault();
      navigate(flatResults[activeIndex]);
    }
  };

  if (!open) return null;

  let itemIndex = -1;

  return (
    <div
      className="fixed inset-0 z-[100] flex items-start justify-center pt-[20vh] px-4"
      onClick={() => setOpen(false)}
    >
      <div className="fixed inset-0 bg-black/40 backdrop-blur-sm" />
      <div
        className="relative w-full max-w-lg bg-paper-2 rounded-2xl shadow-2xl border border-line overflow-hidden animate-[scaleIn_0.15s_ease-out]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Search input */}
        <div className="flex items-center gap-3 px-4 py-3 border-b border-line/60">
          <Search className="w-5 h-5 text-gray-400 shrink-0" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActiveIndex(0);
            }}
            onKeyDown={handleKeyDown}
            placeholder="Search pages, games, players, awards, tools..."
            className="flex-1 text-sm bg-transparent text-gray-800 dark:text-gray-200 placeholder-gray-400 outline-none"
          />
          <kbd className="hidden sm:inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-bold text-gray-400 bg-paper border border-line/60">
            ESC
          </kbd>
        </div>

        {/* Results */}
        <div ref={listRef} className="max-h-[320px] overflow-y-auto py-2">
          {flatResults.length === 0 && !searching ? (
            <div className="px-4 py-6 text-sm text-gray-400 text-center">
              No results found
            </div>
          ) : (
            categoryOrder.map((cat) => {
              const items = grouped[cat];
              if (!items?.length) return null;
              return (
                <div key={cat}>
                  <div className="px-4 py-1.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                    {cat}
                  </div>
                  {items.map((result) => {
                    itemIndex++;
                    const idx = itemIndex;
                    const Icon = result.icon ? iconMap[result.icon] : null;
                    const isActive = idx === activeIndex;
                    return (
                      <button
                        key={result.id}
                        data-index={idx}
                        onClick={() => navigate(result)}
                        onMouseEnter={() => setActiveIndex(idx)}
                        className={`w-full flex items-center gap-3 px-4 py-2 text-sm transition-colors cursor-pointer ${
                          isActive
                            ? "bg-orange-500/10 text-orange-500"
                            : "text-gray-700 dark:text-gray-300 hover:bg-paper/60"
                        }`}
                      >
                        {result.avatar ? (
                          <PixelIcon
                            src={result.avatar}
                            alt={result.label}
                            size={20}
                            imgClassName="rounded"
                          />
                        ) : Icon ? (
                          <Icon className="w-4 h-4 shrink-0" />
                        ) : (
                          <Search className="w-4 h-4 shrink-0 opacity-40" />
                        )}
                        <span className="flex-1 text-left truncate font-medium">
                          {result.label}
                        </span>
                        {result.external && (
                          <ExternalLink className="w-3.5 h-3.5 opacity-40 shrink-0" />
                        )}
                      </button>
                    );
                  })}
                </div>
              );
            })
          )}
          {searching && (
            <div className="px-4 py-2 text-sm text-gray-400">
              Searching players...
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
