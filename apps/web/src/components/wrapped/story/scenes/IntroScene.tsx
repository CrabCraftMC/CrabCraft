"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import SkinReveal3D from "../primitives/SkinReveal3D";
import FloatingParticles from "../primitives/FloatingParticles";
import SplitHeading from "../primitives/SplitHeading";
import type { WrappedData } from "@/lib/wrappedTypes";

export default function IntroScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".intro-eyebrow, .intro-season-pill, .intro-skin, .intro-fog, .intro-chroma",
          { opacity: 1, y: 0, scale: 1, filter: "blur(0px)" }
        );
        gsap.set(".intro-fog", { opacity: 0 });
        return;
      }

      // Background fog plane peels back
      gsap.fromTo(
        ".intro-fog",
        { opacity: 0.9, scale: 1.4 },
        { opacity: 0, scale: 1, duration: 1.6, ease: "expo.out" }
      );

      // 3D skin viewer rises through the fog
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

      // Chromatic aberration sweep on the title
      gsap.fromTo(
        ".intro-chroma",
        { opacity: 0.8, x: 0 },
        {
          opacity: 0,
          x: 8,
          duration: 0.55,
          ease: "power2.out",
          delay: 1.05,
        }
      );

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
            className="intro-star absolute block rounded-full bg-white"
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

      {/* Soft fog plane that peels back */}
      <div
        aria-hidden
        className="intro-fog pointer-events-none absolute inset-0 -z-10"
        style={{
          background:
            "radial-gradient(ellipse at center, rgba(255,255,255,0.15) 0%, rgba(0,0,0,0.85) 70%)",
        }}
      />

      <FloatingParticles count={20} color="rgba(255, 200, 150, 0.7)" />

      <div className="relative flex flex-col items-center gap-6 sm:gap-10">
        <div className="intro-skin">
          <SkinReveal3D
            uuid={data.playerUuid}
            playerName={data.playerName}
            width={240}
            height={320}
          />
        </div>

        <div className="text-center">
          <p className="intro-eyebrow font-mc text-xs uppercase tracking-[0.5em] text-white/70 sm:text-sm">
            CrabCraft Wrapped
          </p>
          <div className="relative mt-3 inline-block">
            <span
              aria-hidden
              className="intro-chroma pointer-events-none absolute inset-0 font-mc text-5xl font-bold leading-none text-cyan-400 sm:text-7xl lg:text-8xl"
              style={{ mixBlendMode: "screen", transform: "translateX(-3px)" }}
            >
              {data.playerName}
            </span>
            <span
              aria-hidden
              className="intro-chroma pointer-events-none absolute inset-0 font-mc text-5xl font-bold leading-none text-rose-500 sm:text-7xl lg:text-8xl"
              style={{ mixBlendMode: "screen", transform: "translateX(3px)" }}
            >
              {data.playerName}
            </span>
            <SplitHeading
              text={data.playerName}
              as="h1"
              className="relative font-mc text-5xl font-bold leading-none text-orange-300 sm:text-7xl lg:text-8xl"
              fromY={-220}
              fromRotateX={-85}
              ease="crab-smash"
              duration={0.95}
              stagger={0.05}
              delay={0.85}
            />
          </div>
          <div className="intro-season-pill mt-6 inline-flex items-center justify-center rounded-full border border-orange-400/30 bg-orange-500/10 px-4 py-1.5 font-mc text-xs uppercase tracking-widest text-orange-200 backdrop-blur-sm">
            Season {data.season}
          </div>
        </div>
      </div>
    </SceneShell>
  );
}
