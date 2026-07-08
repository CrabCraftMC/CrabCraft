"use client";

import { useEffect, useState } from "react";
import Image from "next/image";

const STORAGE_KEY = "crabcraft-mascot-dismissed";
const JOIN_URL = "https://discord.crabcraft.net";
const APPEAR_DELAY_MS = 1500;

export default function MascotJoin() {
  // `mounted` gates rendering until after hydration (localStorage is client-only);
  // `visible` drives the entrance animation a beat later.
  const [mounted, setMounted] = useState(false);
  const [visible, setVisible] = useState(false);
  const [dismissed, setDismissed] = useState(true);

  useEffect(() => {
    const isDismissed = localStorage.getItem(STORAGE_KEY) === "1";
    setDismissed(isDismissed);
    setMounted(true);
    if (isDismissed) return;
    const id = setTimeout(() => setVisible(true), APPEAR_DELAY_MS);
    return () => clearTimeout(id);
  }, []);

  const dismiss = () => {
    localStorage.setItem(STORAGE_KEY, "1");
    setVisible(false);
    // Let the exit transition play before unmounting.
    setTimeout(() => setDismissed(true), 200);
  };

  if (!mounted || dismissed) return null;

  return (
    <div
      className={`fixed bottom-4 right-4 z-40 flex flex-col items-end gap-2 transition-all duration-300 ease-out ${
        visible ? "opacity-100 translate-y-0" : "opacity-0 translate-y-4 pointer-events-none"
      }`}
    >
      {/* Speech bubble. `group` so hovering the bubble highlights both the bubble
          and its tail together (the tail is a sibling, so plain hover can't reach it). */}
      <div className="group relative mr-2 max-w-[220px]">
        <a
          href={JOIN_URL}
          target="_blank"
          rel="noopener noreferrer"
          data-umami-event="mascot-join-smp"
          data-umami-event-source="bubble"
          className="block rounded-2xl bg-paper-2 border border-line shadow-lg pl-3.5 pr-7 py-2 font-mc text-sm text-gray-700 dark:text-gray-200 group-hover:border-orange-400 transition-colors"
        >
          Hey, you should join<br />our SMP! <span aria-hidden>❤️</span>
        </a>
        {/* Bubble tail pointing down toward the mascot — mirrors the bubble's hover glow */}
        <div className="absolute -bottom-1.5 right-6 w-3 h-3 bg-paper-2 border-b border-r border-line group-hover:border-orange-400 rotate-45 transition-colors" />
        {/* Dismiss */}
        <button
          type="button"
          onClick={dismiss}
          aria-label="Dismiss"
          className="absolute bottom-1 right-1.5 w-5 h-5 flex items-center justify-center rounded-full text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors text-xs leading-none cursor-pointer"
        >
          ✕
        </button>
      </div>

      {/* Mascot — no container; Crabby is a transparent bust. Bob animation on the
          inner wrapper, hover-scale on the link, so the two transforms don't fight.
          A silhouette drop-shadow gives depth without a box behind the character. */}
      <a
        href={JOIN_URL}
        target="_blank"
        rel="noopener noreferrer"
        aria-label="Join our Minecraft server on Discord"
        data-umami-event="mascot-join-smp"
        data-umami-event-source="mascot"
        className="block transition-transform hover:scale-105"
      >
        <div className="mascot-bob w-20 h-20">
          <Image
            src="/crabby.webp"
            alt=""
            width={96}
            height={96}
            className="w-full h-full object-contain [filter:drop-shadow(0_4px_6px_rgba(0,0,0,0.35))]"
          />
        </div>
      </a>
    </div>
  );
}
