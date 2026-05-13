"use client";

import Link from "next/link";
import dynamic from "next/dynamic";
import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useIsDark } from "../hooks/useIsDark";
import { useIsMobile } from "../hooks/useIsMobile";
import SplitHeading from "../primitives/SplitHeading";
import FloatingParticles from "../primitives/FloatingParticles";
import StatPill from "../primitives/StatPill";
import type { WrappedData } from "@/lib/wrappedTypes";

const Fireworks = dynamic(
  () => import("@fireworks-js/react").then((m) => m.Fireworks),
  { ssr: false, loading: () => null }
);

function formatTime(seconds: number) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  return days > 0 ? `${days}d ${hours}h` : `${hours}h`;
}

export default function SummaryScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const isDark = useIsDark();
  const isMobile = useIsMobile();

  const pills = [
    { label: "Play Time", value: formatTime(data.stats.play_time_seconds) },
    {
      label: "Distance",
      value: `${(data.stats.total_distance_m / 1000).toFixed(1)} km`,
    },
    {
      label: "Blocks",
      value: data.stats.total_blocks_mined.toLocaleString(),
    },
    { label: "Kills", value: data.stats.mob_kills.toLocaleString() },
    { label: "Crafted", value: data.stats.total_items_crafted.toLocaleString() },
    { label: "Deaths", value: data.stats.deaths.toLocaleString() },
  ];

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".summary-eyebrow, .summary-pill, .summary-cta, .summary-tagline",
          { opacity: 1, y: 0 }
        );
        return;
      }

      const tl = gsap.timeline();
      tl.from(".summary-eyebrow", {
        opacity: 0,
        y: 14,
        duration: 0.5,
      })
        .from(
          ".summary-pill",
          {
            opacity: 0,
            y: 36,
            scale: 0.85,
            filter: "blur(8px)",
            duration: 0.55,
            ease: "back.out(1.6)",
            stagger: { each: 0.07, from: "random" },
          },
          "+=0.3"
        )
        .from(
          ".summary-tagline",
          {
            opacity: 0,
            y: 18,
            duration: 0.5,
            ease: "power3.out",
          },
          "-=0.1"
        )
        .from(
          ".summary-cta",
          {
            opacity: 0,
            y: 14,
            duration: 0.5,
            ease: "back.out(1.5)",
          },
          "-=0.2"
        );
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="summary" title="That's a wrap" ref={ref}>
      <FloatingParticles
        count={28}
        color={isDark ? "rgba(255, 200, 150, 0.7)" : "rgba(200, 100, 30, 0.55)"}
      />

      {!reduced && (
        <div className="pointer-events-none absolute inset-0 -z-10">
          <Fireworks
            options={{
              opacity: isDark ? 0.45 : 0.7,
              acceleration: 1.0,
              particles: isMobile ? 28 : 50,
              intensity: isMobile ? 12 : 18,
              explosion: 4,
              hue: { min: 0, max: 360 },
              traceLength: 3,
              traceSpeed: 8,
            }}
            style={{
              top: 0,
              left: 0,
              width: "100%",
              height: "100%",
              position: "absolute",
            }}
          />
        </div>
      )}

      <div className="relative text-center">
        <p className="summary-eyebrow text-xs uppercase tracking-[0.5em] dark:text-white/60 text-stone-600">
          That&apos;s a wrap
        </p>
        <div className="mt-4">
          <SplitHeading
            text={data.playerName}
            as="h2"
            className="inline-block text-5xl font-bold leading-none dark:text-orange-300 text-orange-700 drop-shadow-[0_4px_24px_rgba(255,140,80,0.3)] sm:text-7xl"
            fromY={-160}
            fromRotateX={-60}
            ease="crab-smash"
            duration={0.85}
            stagger={0.05}
            delay={0.45}
          />
        </div>

        <div className="mt-6 grid grid-cols-2 gap-3 sm:mt-8 sm:grid-cols-3 sm:gap-4">
          {pills.map((p) => (
            <div key={p.label} className="summary-pill">
              <StatPill label={p.label} value={p.value} />
            </div>
          ))}
        </div>

        <p className="summary-tagline mt-4 text-base dark:text-white/70 text-stone-600 sm:mt-6 sm:text-lg">
          See you next season.
        </p>

        <div className="mt-4 flex justify-center sm:mt-6">
          <Link
            href="/wrapped"
            className="summary-cta inline-flex h-11 items-center justify-center gap-2 rounded-full bg-orange-500 px-6 text-sm font-bold text-white shadow-lg shadow-orange-500/25 transition-transform hover:scale-105"
          >
            Other seasons
          </Link>
        </div>
      </div>
    </SceneShell>
  );
}
