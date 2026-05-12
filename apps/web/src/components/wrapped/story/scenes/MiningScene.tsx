"use client";

import { useMemo, useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import DigitRoller from "../primitives/DigitRoller";
import McIdTexture from "@/components/wrapped/shared/McIdTexture";
import { useHaptics } from "../hooks/useHaptics";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getMiningJoke } from "../jokes/miningJokes";

const RAIN_BLOCKS = [
  "block/stone.png",
  "block/dirt.png",
  "block/cobblestone.png",
  "block/diamond_ore.png",
  "block/iron_ore.png",
  "block/coal_ore.png",
  "block/oak_planks.png",
  "block/grass_block_top.png",
];

function formatId(id: string | null | undefined) {
  if (!id) return null;
  return id.replace(/^minecraft:/, "").replace(/_/g, " ");
}

export default function MiningScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const haptics = useHaptics();
  const total = data.stats.total_blocks_mined;
  const rank = data.ranks.total_blocks_mined;
  const top = data.stats.top_block_mined;

  const rain = useMemo(
    () =>
      Array.from({ length: 40 }, (_, i) => ({
        texture: RAIN_BLOCKS[i % RAIN_BLOCKS.length],
        x: 5 + (i * 71) % 90,
        size: 20 + (i % 4) * 8,
        delay: 0.1 + Math.random() * 1.6,
        rotation: (Math.random() - 0.5) * 90,
      })),
    []
  );

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".mining-eyebrow, .mining-number, .mining-unit, .mining-top, .mining-meta, .mining-joke, .mining-block",
          { opacity: 1, y: 0, scale: 1, rotation: 0 }
        );
        return;
      }
      const blocks = gsap.utils.toArray<HTMLElement>(".mining-block");
      gsap.set(blocks, { y: "-110vh", opacity: 1 });
      gsap.to(blocks, {
        y: "0vh",
        duration: 1.1,
        ease: "bounce.out",
        stagger: { each: 0.025, from: "random" },
        delay: 0.15,
        onUpdate() {
          // Fire a light tick for the first dozen impacts so it feels tactile.
        },
      });
      // Fade rain to ambient after landing
      gsap.to(blocks, {
        opacity: 0.18,
        duration: 0.6,
        delay: 1.6,
        ease: "power2.out",
      });

      const tl = gsap.timeline({ delay: 1.2 });
      tl.from(".mining-eyebrow", { opacity: 0, y: 18, duration: 0.5 })
        .from(
          ".mining-number",
          {
            opacity: 0,
            scale: 0.6,
            duration: 0.7,
            ease: "back.out(2)",
            onStart: () => haptics.medium(),
          },
          "-=0.2"
        )
        .from(".mining-unit", { opacity: 0, x: 16, duration: 0.4 }, "-=0.2")
        .from(".mining-top", { opacity: 0, y: 24, scale: 0.9, duration: 0.6, ease: "back.out(1.6)" }, "-=0.1")
        .from(".mining-meta", { opacity: 0, y: 12, duration: 0.4 }, "-=0.2")
        .from(".mining-joke", { opacity: 0, y: 14, filter: "blur(8px)", duration: 0.5 }, "-=0.1");
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="mining" title="Mining" ref={ref}>
      {/* Block rain layer */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 overflow-hidden"
      >
        {rain.map((b, i) => (
          <img
            key={i}
            src={`/minecraft/${b.texture}`}
            alt=""
            className="mining-block pixelated absolute"
            style={{
              left: `${b.x}%`,
              top: `${30 + ((i * 13) % 50)}%`,
              width: b.size,
              height: b.size,
              transform: `rotate(${b.rotation}deg)`,
            }}
          />
        ))}
      </div>

      <div className="text-center">
        <p className="mining-eyebrow font-mc text-xs uppercase tracking-[0.5em] text-white/60">
          You mined
        </p>
        <p className="mt-6 font-mc text-6xl font-bold text-emerald-200 sm:text-8xl lg:text-9xl">
          <span className="mining-number inline-block">
            <DigitRoller value={total} duration={1.6} delay={1.4} />
          </span>
          <span className="mining-unit ml-3 inline-block text-3xl text-white/70 sm:text-4xl">
            blocks
          </span>
        </p>

        {top && (
          <div className="mining-top mx-auto mt-10 inline-flex items-center gap-4 rounded-2xl border border-white/10 bg-white/5 px-6 py-4 backdrop-blur-sm">
            <McIdTexture id={top.id} size={48} />
            <div className="text-left">
              <p className="text-xs uppercase tracking-widest text-white/60">
                Favourite block
              </p>
              <p className="font-mc text-xl font-bold capitalize text-white">
                {formatId(top.id)}
              </p>
              <p className="font-mc text-sm text-white/70">
                {top.count.toLocaleString()}× mined
              </p>
            </div>
          </div>
        )}

        <p className="mining-meta mt-6 text-sm text-white/60">
          Ranked <span className="font-mc font-bold text-white">#{rank}</span> of {data.totalPlayers}
        </p>

        <p className="mining-joke mt-6 text-balance text-base italic text-white/70 sm:text-lg">
          {getMiningJoke(total)}
        </p>
      </div>
    </SceneShell>
  );
}
