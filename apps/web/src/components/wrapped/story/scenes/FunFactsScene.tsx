"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useHaptics } from "../hooks/useHaptics";
import DigitRoller from "../primitives/DigitRoller";
import Squircle from "@/components/Squircle";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getFunFactsJoke } from "../jokes/funFactsJokes";

interface Fact {
  key: keyof WrappedData["stats"];
  label: string;
  texture: string;
  accent: string;
}

const FACTS: Fact[] = [
  { key: "jumps", label: "Jumps", texture: "/minecraft/item/rabbit_foot.png", accent: "from-pink-500/20 to-pink-500/5" },
  { key: "animals_bred", label: "Animals bred", texture: "/minecraft/item/wheat.png", accent: "from-yellow-500/20 to-yellow-500/5" },
  { key: "fish_caught", label: "Fish caught", texture: "/minecraft/item/cod.png", accent: "from-cyan-500/20 to-cyan-500/5" },
  { key: "villagers_traded", label: "Villager trades", texture: "/minecraft/item/emerald.png", accent: "from-emerald-500/20 to-emerald-500/5" },
  { key: "enchantments", label: "Enchantments", texture: "/minecraft/item/enchanted_book.png", accent: "from-violet-500/20 to-violet-500/5" },
  { key: "times_slept", label: "Times slept", texture: "/minecraft/block/red_wool.png", accent: "from-rose-500/20 to-rose-500/5" },
];

export default function FunFactsScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const haptics = useHaptics();

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(".ff-eyebrow, .ff-card, .ff-joke", { opacity: 1, y: 0, scale: 1, rotation: 0 });
        return;
      }
      gsap.from(".ff-eyebrow", { opacity: 0, y: 18, duration: 0.5 });
      gsap.fromTo(
        ".ff-card",
        {
          opacity: 0,
          y: () => gsap.utils.random(-150, 150),
          x: () => gsap.utils.random(-200, 200),
          scale: 0.6,
          rotation: () => gsap.utils.random(-30, 30),
          filter: "blur(12px)",
        },
        {
          opacity: 1,
          x: 0,
          y: 0,
          scale: 1,
          rotation: 0,
          filter: "blur(0px)",
          duration: 0.9,
          ease: "back.out(1.6)",
          stagger: { each: 0.08, from: "random" },
          delay: 0.3,
          onStart: () => {
            // One tick per card, paced to the visual stagger.
            const count = document.querySelectorAll(".ff-card").length;
            for (let i = 0; i < count; i++) {
              gsap.delayedCall(i * 0.08, () => haptics.tick());
            }
          },
        }
      );
      gsap.from(".ff-joke", {
        opacity: 0,
        y: 14,
        filter: "blur(8px)",
        duration: 0.5,
        delay: 1.4,
      });
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="fun-facts" title="Fun Facts" ref={ref}>
      <div className="text-center">
        <p className="ff-eyebrow text-xs uppercase tracking-[0.5em] dark:text-white/60 text-stone-600">
          Fun Facts
        </p>
        <div className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-4">
          {FACTS.map((f) => {
            const value = data.stats[f.key] as number;
            return (
              <Squircle
                key={f.key}
                cornerRadius={16}
                className={`ff-card relative overflow-hidden bg-gradient-to-br p-4 backdrop-blur-sm ${f.accent}`}
              >
                <img
                  src={f.texture}
                  alt=""
                  width={28}
                  height={28}
                  className="ff-icon pixelated mb-2"
                  style={{ display: "inline-block" }}
                />
                <p className="font-mc text-2xl font-bold tabular-nums dark:text-stone-100 text-stone-800 sm:text-3xl">
                  <DigitRoller value={value} duration={1.2} delay={1.0} haptic={false} />
                </p>
                <p className="mt-1 text-[10px] uppercase tracking-widest dark:text-white/60 text-stone-600">
                  {f.label}
                </p>
              </Squircle>
            );
          })}
        </div>
        <p className="ff-joke mt-8 text-balance text-base italic dark:text-white/70 text-stone-600 sm:text-lg">
          {getFunFactsJoke(data.stats)}
        </p>
      </div>
    </SceneShell>
  );
}
