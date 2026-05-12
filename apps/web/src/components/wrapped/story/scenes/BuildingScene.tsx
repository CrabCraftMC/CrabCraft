"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useHaptics } from "../hooks/useHaptics";
import DigitRoller from "../primitives/DigitRoller";
import McIdTexture from "@/components/wrapped/shared/McIdTexture";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getBuildingJoke } from "../jokes/buildingJokes";

const TOWER_BRICKS = [
  { texture: "block/oak_planks.png", width: 64 },
  { texture: "block/cobblestone.png", width: 70 },
  { texture: "block/bricks.png", width: 60 },
  { texture: "block/stone.png", width: 66 },
  { texture: "block/oak_planks.png", width: 56 },
  { texture: "block/cobblestone.png", width: 62 },
  { texture: "block/bricks.png", width: 52 },
  { texture: "block/stone.png", width: 58 },
  { texture: "block/oak_planks.png", width: 48 },
  { texture: "block/cobblestone.png", width: 50 },
  { texture: "block/bricks.png", width: 44 },
  { texture: "block/stone.png", width: 38 },
];

function formatId(id: string | null | undefined) {
  if (!id) return null;
  return id.replace(/^minecraft:/, "").replace(/_/g, " ");
}

export default function BuildingScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const haptics = useHaptics();
  const { total_items_crafted, total_blocks_placed, top_item_crafted, top_item_used } = data.stats;
  const rank = data.ranks.total_items_crafted;

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".build-eyebrow, .build-brick, .build-stats, .build-top, .build-meta, .build-joke",
          { opacity: 1, y: 0, scaleY: 1 }
        );
        return;
      }
      // Tower stacks bottom-up with squeeze + per-brick haptic
      const bricks = gsap.utils.toArray<HTMLElement>(".build-brick");
      bricks.forEach((b, i) => {
        gsap.fromTo(
          b,
          { y: -400, opacity: 0 },
          {
            y: 0,
            opacity: 1,
            duration: 0.6,
            ease: "bounce.out",
            delay: 0.3 + i * 0.08,
            onStart: () => haptics.light(),
          }
        );
        gsap.to(b, {
          scaleY: 0.85,
          duration: 0.08,
          delay: 0.3 + i * 0.08 + 0.55,
          yoyo: true,
          repeat: 1,
          ease: "power2.out",
        });
      });

      const tl = gsap.timeline({ delay: 1.6 });
      tl.from(".build-eyebrow", { opacity: 0, y: 18, duration: 0.5 })
        .from(
          ".build-stats",
          { opacity: 0, y: 24, scale: 0.85, duration: 0.6, ease: "back.out(1.7)", stagger: 0.12 },
          "-=0.2"
        )
        .from(".build-top", { opacity: 0, y: 18, duration: 0.5, stagger: 0.1 }, "-=0.2")
        .from(".build-meta", { opacity: 0, y: 12, duration: 0.4 }, "-=0.2")
        .from(".build-joke", { opacity: 0, y: 14, filter: "blur(8px)", duration: 0.5 }, "-=0.1");
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="building" title="Building & Crafting" ref={ref}>
      <div className="grid items-center gap-10 lg:grid-cols-[1fr_auto_1fr]">
        {/* Tower */}
        <div className="flex flex-col-reverse items-center gap-1.5">
          {TOWER_BRICKS.map((b, i) => (
            <img
              key={i}
              src={`/minecraft/${b.texture}`}
              alt=""
              className="build-brick pixelated"
              style={{
                width: b.width,
                height: 16,
                objectFit: "cover",
                imageRendering: "pixelated",
                transformOrigin: "center bottom",
              }}
            />
          ))}
        </div>

        <div className="hidden h-1 w-32 bg-white/10 lg:block" />

        <div className="text-center lg:text-left">
          <p className="build-eyebrow font-mc text-xs uppercase tracking-[0.5em] text-white/60">
            You crafted & built
          </p>

          <div className="mt-6 space-y-4">
            <div className="build-stats">
              <p className="font-mc text-5xl font-bold text-sky-200 sm:text-7xl">
                <DigitRoller value={total_items_crafted} duration={1.5} delay={1.8} />
              </p>
              <p className="mt-1 text-xs uppercase tracking-widest text-white/60">
                Items crafted
              </p>
            </div>
            <div className="build-stats">
              <p className="font-mc text-4xl font-bold text-indigo-200 sm:text-6xl">
                <DigitRoller value={total_blocks_placed} duration={1.5} delay={2.0} />
              </p>
              <p className="mt-1 text-xs uppercase tracking-widest text-white/60">
                Blocks placed
              </p>
            </div>
          </div>

          <div className="mt-6 grid gap-3 sm:grid-cols-2">
            {top_item_crafted && (
              <div className="build-top flex items-center gap-3 rounded-2xl border border-white/10 bg-white/5 p-3 backdrop-blur-sm">
                <McIdTexture id={top_item_crafted.id} size={36} />
                <div className="min-w-0 text-left">
                  <p className="text-[10px] uppercase tracking-widest text-white/60">
                    Most crafted
                  </p>
                  <p className="truncate font-mc text-sm capitalize text-white">
                    {formatId(top_item_crafted.id)}
                  </p>
                </div>
              </div>
            )}
            {top_item_used && (
              <div className="build-top flex items-center gap-3 rounded-2xl border border-white/10 bg-white/5 p-3 backdrop-blur-sm">
                <McIdTexture id={top_item_used.id} size={36} />
                <div className="min-w-0 text-left">
                  <p className="text-[10px] uppercase tracking-widest text-white/60">
                    Most used
                  </p>
                  <p className="truncate font-mc text-sm capitalize text-white">
                    {formatId(top_item_used.id)}
                  </p>
                </div>
              </div>
            )}
          </div>

          <p className="build-meta mt-6 text-sm text-white/60">
            Ranked <span className="font-mc font-bold text-white">#{rank}</span> for crafting
          </p>
          <p className="build-joke mt-4 text-balance text-base italic text-white/70 sm:text-lg">
            {getBuildingJoke(total_items_crafted, total_blocks_placed)}
          </p>
        </div>
      </div>
    </SceneShell>
  );
}
