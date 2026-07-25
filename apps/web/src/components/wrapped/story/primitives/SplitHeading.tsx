"use client";

import { createElement, useRef, type ElementType } from "react";
import { useGSAP } from "@gsap/react";
import { gsap, SplitText } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";

interface Props {
  text: string;
  as?: ElementType;
  className?: string;
  /** GSAP split type. Defaults to "chars". */
  split?: "chars" | "words" | "lines";
  /** Starting Y offset for the from-state. */
  fromY?: number;
  /** Starting rotation X for a smash-from-above feel. */
  fromRotateX?: number;
  /** GSAP ease. Defaults to "crab-smash". */
  ease?: string;
  duration?: number;
  /** Per-unit stagger in seconds. */
  stagger?: number;
  delay?: number;
  /** Aria label for the screen reader (falls back to text). */
  ariaLabel?: string;
}

/**
 * SplitText-driven heading. Each char/word slams into place via GSAP with
 * the smash ease and configurable from-Y/rotateX. Auto-cleans on unmount;
 * SplitText is reverted in the timeline's onComplete to keep DOM lean.
 */
export default function SplitHeading({
  text,
  as,
  className,
  split = "chars",
  fromY = -180,
  fromRotateX = -75,
  ease = "crab-smash",
  duration = 0.85,
  stagger = 0.045,
  delay = 0,
  ariaLabel,
}: Props) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const Tag = (as ?? "h1") as ElementType;

  useGSAP(
    () => {
      const el = ref.current;
      if (!el) return;
      // Re-render-safe: clear any previous split before re-splitting.
      const splitInstance = new SplitText(el, {
        type: split,
        charsClass: "split-char",
        wordsClass: "split-word",
        linesClass: "split-line",
      });
      const targets =
        split === "chars"
          ? splitInstance.chars
          : split === "words"
            ? splitInstance.words
            : splitInstance.lines;

      if (reduced) {
        gsap.set(targets, { opacity: 1, y: 0, rotateX: 0 });
        return () => splitInstance.revert();
      }

      gsap.set(targets, {
        opacity: 0,
        y: fromY,
        rotateX: fromRotateX,
        transformPerspective: 1000,
      });
      const tl = gsap.timeline({ delay });
      tl.to(targets, {
        opacity: 1,
        y: 0,
        rotateX: 0,
        duration,
        ease,
        stagger,
        onComplete: () => {
          // Revert the split DOM back to a single text node so accessibility
          // tools and copy/paste behave correctly.
          splitInstance.revert();
        },
      });
      return () => splitInstance.revert();
    },
    { scope: ref, dependencies: [text, reduced] }
  );

  return createElement(
    Tag,
    {
      ref,
      className,
      "aria-label": ariaLabel ?? text,
      style: { display: "inline-block", perspective: "1000px" },
    },
    text
  );
}
