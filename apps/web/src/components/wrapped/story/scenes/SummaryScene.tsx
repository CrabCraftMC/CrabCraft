"use client";

import Link from "next/link";
import dynamic from "next/dynamic";
import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import SplitHeading from "../primitives/SplitHeading";
import FloatingParticles from "../primitives/FloatingParticles";
import StatPill from "../primitives/StatPill";
import { SLIDE_WAVE_COLORS } from "../data/sceneOrder";
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
          ".summary-eyebrow, .summary-pill, .summary-cta, .summary-tagline, .summary-thumb",
          { opacity: 1, y: 0 }
        );
        return;
      }

      // Background montage thumbnails streak diagonally past
      const thumbs = gsap.utils.toArray<HTMLElement>(".summary-thumb");
      thumbs.forEach((t, i) => {
        gsap.set(t, { x: "100vw", y: gsap.utils.random(-40, 40) });
        gsap.to(t, {
          x: "-100vw",
          duration: 9 + i * 0.5,
          ease: "none",
          repeat: -1,
          delay: -i * 0.7,
        });
      });

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
            stagger: 0.08,
            ease: "back.out(1.5)",
          },
          "-=0.2"
        );
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="summary" title="That's a wrap" ref={ref}>
      {/* Streaking thumbnail montage in the background */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 overflow-hidden"
      >
        {Array.from({ length: 8 }, (_, i) => {
          const color = SLIDE_WAVE_COLORS[i % SLIDE_WAVE_COLORS.length];
          return (
            <span
              key={i}
              className="summary-thumb absolute rounded-2xl"
              style={{
                top: `${(i / 8) * 100}%`,
                width: 200,
                height: 120,
                background: `linear-gradient(135deg, rgba(${Math.round(color[0] * 255)}, ${Math.round(color[1] * 255)}, ${Math.round(color[2] * 255)}, 0.35), rgba(0,0,0,0.4))`,
                filter: "blur(10px)",
                opacity: 0.65,
              }}
            />
          );
        })}
      </div>

      <FloatingParticles count={28} color="rgba(255, 200, 150, 0.7)" />

      {!reduced && (
        <div className="pointer-events-none absolute inset-0 -z-10">
          <Fireworks
            options={{
              opacity: 0.45,
              acceleration: 1.0,
              particles: 50,
              intensity: 18,
              explosion: 4,
              hue: { min: 5, max: 60 },
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
        <p className="summary-eyebrow font-mc text-xs uppercase tracking-[0.5em] text-white/60">
          That&apos;s a wrap
        </p>
        <div className="mt-4">
          <SplitHeading
            text={data.playerName}
            as="h2"
            className="holo-text inline-block font-mc text-5xl font-bold leading-none drop-shadow-[0_4px_24px_rgba(255,140,80,0.4)] sm:text-7xl"
            fromY={-160}
            fromRotateX={-60}
            ease="crab-smash"
            duration={0.85}
            stagger={0.05}
            delay={0.45}
          />
        </div>

        <div className="mt-10 grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-4">
          {pills.map((p) => (
            <div key={p.label} className="summary-pill">
              <StatPill label={p.label} value={p.value} />
            </div>
          ))}
        </div>

        <p className="summary-tagline mt-8 text-base text-white/70 sm:text-lg">
          See you next season.
        </p>

        <div className="mt-6 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Link
            href={`/wrapped/${data.season}/dashboard`}
            className="summary-cta inline-flex items-center gap-2 rounded-full bg-orange-500 px-6 py-3 text-sm font-bold text-white shadow-lg shadow-orange-500/25 transition-transform hover:scale-105"
          >
            View Dashboard →
          </Link>
          <Link
            href="/wrapped"
            className="summary-cta inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-6 py-3 text-sm font-bold text-white/80 backdrop-blur-sm transition-colors hover:bg-white/15 hover:text-white"
          >
            Other seasons
          </Link>
        </div>
      </div>
    </SceneShell>
  );
}
