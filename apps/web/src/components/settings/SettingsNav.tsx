"use client";

import { useRef, useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";

const TABS = ["account", "alts", "channels", "display"] as const;
type Tab = (typeof TABS)[number];

const TAB_LABELS: Record<Tab, string> = {
  account: "Account",
  alts: "Alts",
  channels: "Linked Channels",
  display: "Display",
};

export default function SettingsNav() {
  const searchParams = useSearchParams();
  const raw = searchParams.get("tab");
  const activeTab = (TABS.includes(raw as Tab) ? raw : "account") as Tab;

  const navRef = useRef<HTMLElement>(null);
  const tabRefs = useRef<Map<Tab, HTMLAnchorElement>>(new Map());
  const [indicator, setIndicator] = useState({ left: 0, width: 0 });
  const [ready, setReady] = useState(false);
  const [hoveredTab, setHoveredTab] = useState<Tab | null>(null);

  const measureTab = useCallback((tab: Tab) => {
    const el = tabRefs.current.get(tab);
    const nav = navRef.current;
    if (el && nav) {
      const navRect = nav.getBoundingClientRect();
      const tabRect = el.getBoundingClientRect();
      return { left: tabRect.left - navRect.left, width: tabRect.width };
    }
    return null;
  }, []);

  const targetTab = hoveredTab ?? activeTab;

  useEffect(() => {
    const pos = measureTab(targetTab);
    if (pos) {
      setIndicator(pos);
      setReady(true);
    }
  }, [targetTab, measureTab]);

  return (
    <nav
      ref={navRef}
      className="relative mb-6 flex gap-1 overflow-x-auto rounded-xl bg-[var(--paper-2)] p-1 sm:mb-8"
    >
      {ready && (
        <span
          className="absolute top-1 bottom-1 rounded-lg bg-orange-500 shadow-sm transition-all duration-300 ease-in-out"
          style={{ left: indicator.left, width: indicator.width }}
        />
      )}
      {TABS.map((tab) => (
        <Link
          key={tab}
          ref={(el) => { if (el) tabRefs.current.set(tab, el); }}
          href={`/settings?tab=${tab}`}
          onMouseEnter={() => setHoveredTab(tab)}
          onMouseLeave={() => setHoveredTab(null)}
          className={`relative z-10 shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors duration-200 sm:px-4 sm:py-2 sm:text-sm ${
            tab === targetTab
              ? "text-white"
              : "text-[var(--foreground)]/60"
          }`}
        >
          {TAB_LABELS[tab]}
        </Link>
      ))}
    </nav>
  );
}
