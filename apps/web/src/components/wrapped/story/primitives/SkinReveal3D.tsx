"use client";

import { useEffect, useRef } from "react";

interface Props {
  uuid: string;
  playerName: string;
  width?: number;
  height?: number;
  className?: string;
}

/**
 * 3D rotating Minecraft player skin via the `skin3d` library. Lazily imports
 * skin3d on mount so its (and three.js's) cost is paid only when this
 * component is actually rendered. Falls back to a hidden canvas if skin3d
 * fails to load.
 */
export default function SkinReveal3D({
  uuid,
  playerName,
  width = 240,
  height = 340,
  className,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const cleanUuid = uuid.replace(/-/g, "");

  useEffect(() => {
    if (!canvasRef.current) return;
    let viewer: { dispose?: () => void } | null = null;
    let cancelled = false;

    async function init() {
      try {
        const mod = await import("skin3d");
        if (cancelled || !canvasRef.current) return;
        const { Render, WalkingAnimation, NameTagObject } = mod;
        const totalHeight = height + 50;
        const v = new Render({
          canvas: canvasRef.current,
          width,
          height: totalHeight,
          skin: `https://mc-heads.net/skin/${cleanUuid}`,
        });
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const v_any = v as any;
        v_any.autoRotate = true;
        v_any.autoRotateSpeed = 0.6;
        const anim = new WalkingAnimation();
        anim.speed = 0.45;
        v_any.animation = anim;
        if (v_any.camera) {
          v_any.camera.position.y += 2;
          v_any.camera.position.z += 4;
        }
        const tag = new NameTagObject(playerName, {
          textStyle: "white",
        } as ConstructorParameters<typeof NameTagObject>[1]);
        v_any.nameTag = tag;
        viewer = v;
      } catch {
        // skin3d failed to load — leave canvas blank, the parent scene also
        // renders the 2D fallback.
      }
    }

    init();
    return () => {
      cancelled = true;
      if (viewer?.dispose) viewer.dispose();
    };
  }, [cleanUuid, playerName, width, height]);

  return (
    <canvas
      ref={canvasRef}
      width={width}
      height={height + 50}
      style={{ width, height: height + 50 }}
      className={className}
    />
  );
}
