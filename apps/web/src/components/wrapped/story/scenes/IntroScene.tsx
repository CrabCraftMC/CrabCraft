"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useIsDark } from "../hooks/useIsDark";
import FloatingParticles from "../primitives/FloatingParticles";
import SplitHeading from "../primitives/SplitHeading";
import type { WrappedData } from "@/lib/wrappedTypes";

export default function IntroScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const isDark = useIsDark();

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".intro-eyebrow, .intro-season-pill, .intro-skin",
          { opacity: 1, y: 0, scale: 1, filter: "blur(0px)" }
        );
        return;
      }

      // 3D skin viewer rises in
      gsap.from(".intro-skin", {
        y: 240,
        opacity: 0,
        filter: "blur(20px)",
        duration: 1.4,
        ease: "expo.out",
        delay: 0.25,
      });

      // Eyebrow fades in
      gsap.from(".intro-eyebrow", {
        opacity: 0,
        y: 18,
        filter: "blur(10px)",
        duration: 0.6,
        ease: "power3.out",
        delay: 0.65,
      });

      // Season pill bursts in
      gsap.from(".intro-season-pill", {
        scale: 0,
        rotation: -25,
        opacity: 0,
        duration: 0.7,
        ease: "back.out(2.4)",
        delay: 1.5,
      });

      // Parallax stars: each gets a unique slow drift
      const stars = gsap.utils.toArray<HTMLElement>(".intro-star");
      stars.forEach((star) => {
        const dx = gsap.utils.random(-30, 30);
        const dy = gsap.utils.random(-20, 20);
        const dur = gsap.utils.random(10, 22);
        gsap.to(star, {
          x: `+=${dx}`,
          y: `+=${dy}`,
          duration: dur,
          ease: "sine.inOut",
          repeat: -1,
          yoyo: true,
        });
        gsap.fromTo(
          star,
          { opacity: 0 },
          { opacity: gsap.utils.random(0.3, 0.9), duration: 1.2, delay: 0.3 }
        );
      });
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="intro" title="Welcome" ref={ref}>
      {/* Parallax star field */}
      <div aria-hidden className="pointer-events-none absolute inset-0 -z-10">
        {Array.from({ length: 36 }, (_, i) => (
          <span
            key={i}
            className="intro-star absolute block rounded-full dark:bg-white bg-stone-700"
            style={{
              left: `${Math.random() * 100}%`,
              top: `${Math.random() * 100}%`,
              width: 1 + Math.random() * 2,
              height: 1 + Math.random() * 2,
              opacity: 0,
              boxShadow: "0 0 6px rgba(255,255,255,0.7)",
            }}
          />
        ))}
      </div>

      <FloatingParticles
        count={20}
        color={isDark ? "rgba(255, 200, 150, 0.7)" : "rgba(200, 100, 30, 0.55)"}
      />

      <div className="relative flex flex-col items-center gap-6 sm:gap-10">
        <div className="intro-skin">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={`https://mc-api.io/render/full/${data.playerUuid}`}
            alt=""
            width={480}
            height={640}
            className="h-auto w-[240px] object-contain"
          />
        </div>

        <div className="text-center">
          <p className="intro-eyebrow text-xs uppercase tracking-[0.5em] dark:text-white/70 text-stone-600 sm:text-sm">
            CrabCraft Wrapped
          </p>
          <div className="relative mt-3 inline-block">
            <SplitHeading
              text={data.playerName}
              as="h1"
              className="relative text-5xl font-bold leading-none dark:text-orange-300 text-orange-700 sm:text-7xl lg:text-8xl"
              fromY={-220}
              fromRotateX={-85}
              ease="crab-smash"
              duration={0.95}
              stagger={0.05}
              delay={0.85}
            />
          </div>
          <p className="intro-season-pill mt-4 text-xs uppercase tracking-[0.4em] dark:text-orange-200/80 text-orange-700/80">
            Season {data.season}
          </p>
        </div>
      </div>
    </SceneShell>
  );
}
