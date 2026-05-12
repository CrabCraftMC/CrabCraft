"use client";

import { useEffect, useMemo, useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";

interface Props {
  count?: number;
  color?: string;
  className?: string;
}

interface ParticleSpec {
  x: number;
  delay: number;
  duration: number;
  drift: number;
  size: number;
  opacity: number;
}

/**
 * Ambient floating particle field. Particles rise from the bottom over a
 * randomized duration; pure GSAP infinite tweens so they pause cleanly with
 * the rest of the scene timeline.
 */
export default function FloatingParticles({
  count = 24,
  color = "rgba(255, 220, 180, 0.8)",
  className,
}: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const reduced = useReducedMotion();

  const specs = useMemo<ParticleSpec[]>(
    () =>
      Array.from({ length: count }, () => ({
        x: Math.random() * 100,
        delay: -Math.random() * 8,
        duration: 8 + Math.random() * 12,
        drift: (Math.random() - 0.5) * 80,
        size: 2 + Math.random() * 3,
        opacity: 0.35 + Math.random() * 0.45,
      })),
    [count]
  );

  useEffect(() => {
    if (reduced || !ref.current) return;
    const tweens: gsap.core.Tween[] = [];
    const particles = ref.current.querySelectorAll<HTMLElement>(".fp-particle");
    particles.forEach((p, i) => {
      const s = specs[i];
      gsap.set(p, { y: "100vh", x: 0, opacity: 0 });
      const tween = gsap.to(p, {
        y: "-10vh",
        x: s.drift,
        opacity: s.opacity,
        delay: s.delay,
        duration: s.duration,
        ease: "none",
        repeat: -1,
        modifiers: {
          y: gsap.utils.unitize((y: number) => {
            // wrap so y always stays within the rising path
            const v = parseFloat(String(y));
            return v;
          }, "vh"),
        },
        keyframes: [
          { opacity: 0, y: "100vh", duration: 0 },
          { opacity: s.opacity, duration: s.duration * 0.1 },
          { opacity: s.opacity, duration: s.duration * 0.8 },
          { opacity: 0, y: "-10vh", duration: s.duration * 0.1 },
        ],
      });
      tweens.push(tween);
    });
    return () => {
      tweens.forEach((t) => t.kill());
    };
  }, [specs, reduced]);

  return (
    <div
      ref={ref}
      aria-hidden
      className={`pointer-events-none absolute inset-0 overflow-hidden ${className ?? ""}`}
    >
      {specs.map((s, i) => (
        <span
          key={i}
          className="fp-particle absolute rounded-full"
          style={{
            left: `${s.x}%`,
            width: s.size,
            height: s.size,
            background: color,
            boxShadow: `0 0 ${s.size * 3}px ${color}`,
            willChange: "transform, opacity",
          }}
        />
      ))}
    </div>
  );
}
