"use client";

import { useRef, useState, useLayoutEffect, type ComponentPropsWithoutRef } from "react";
import { getSvgPath } from "figma-squircle";

interface SquircleProps extends ComponentPropsWithoutRef<"div"> {
  cornerRadius?: number;
  cornerSmoothing?: number;
}

export default function Squircle({
  cornerRadius = 32,
  cornerSmoothing = 1,
  style,
  children,
  ...props
}: SquircleProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [clipPath, setClipPath] = useState<string | undefined>(undefined);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;

    // Use offsetWidth/Height (layout dimensions, transform-independent) so
    // GSAP entry tweens (scale, rotate, translate) don't bake a transformed
    // size into the clip-path. Measured synchronously in useLayoutEffect so
    // the squircle is set before paint — avoids a flash of rounded rect, and
    // avoids races with rapid scene transitions where a queued RAF might
    // fire on a torn-down instance.
    const update = () => {
      const width = el.offsetWidth;
      const height = el.offsetHeight;
      if (width > 0 && height > 0) {
        const path = getSvgPath({
          width,
          height,
          cornerRadius,
          cornerSmoothing,
        });
        setClipPath(`path('${path}')`);
      }
    };

    update();

    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [cornerRadius, cornerSmoothing]);

  return (
    <div
      ref={ref}
      style={{
        ...style,
        clipPath,
        // Keep border-radius set even after clip-path lands. clip-path takes
        // visual precedence when it's correct, but if it ever fails to apply
        // (rapid mount/unmount during scene transitions, ResizeObserver miss,
        // bad measurement) the rounded fallback prevents a "plain rectangle"
        // flash.
        borderRadius: cornerRadius,
      }}
      {...props}
    >
      {children}
    </div>
  );
}
