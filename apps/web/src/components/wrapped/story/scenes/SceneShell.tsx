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
      className="relative z-10 flex h-full w-full items-center justify-center px-4 py-12 sm:px-8 md:py-16"
    >
      <div className="w-full max-w-5xl dark:text-stone-100 text-stone-800">{children}</div>
    </section>
  );
});

export default SceneShell;
