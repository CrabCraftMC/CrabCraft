"use client";

import { forwardRef, type ReactNode } from "react";
import type { SceneId } from "../data/sceneOrder";

interface Props {
  id: SceneId;
  title: string;
  children: ReactNode;
}

const SceneShell = forwardRef<HTMLElement, Props>(function SceneShell(
  { id, title, children },
  ref
) {
  return (
    <section
      ref={ref}
      data-scene-id={id}
      role="region"
      aria-roledescription="slide"
      aria-label={title}
      className="relative z-10 flex min-h-screen w-full items-center justify-center px-4 py-24 sm:px-8"
    >
      <div className="w-full max-w-5xl text-white">{children}</div>
    </section>
  );
});

export default SceneShell;
