"use client";

import { useRef, useState, useEffect, type ComponentPropsWithoutRef } from "react";
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

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    const update = () => {
      requestAnimationFrame(() => {
        const { width, height } = el.getBoundingClientRect();
        if (width > 0 && height > 0) {
          const path = getSvgPath({
            width,
            height,
            cornerRadius,
            cornerSmoothing,
          });
          setClipPath(`path('${path}')`);
        }
      });
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
        borderRadius: clipPath ? undefined : cornerRadius,
      }}
      {...props}
    >
      {children}
    </div>
  );
}
