"use client";

import { useState } from "react";
import PixelIcon from "@/components/PixelIcon";
import { mcIdToTextureCandidates } from "@/lib/mc-textures";

interface McIdTextureProps {
  id: string;
  size?: number;
  alt?: string;
  className?: string;
}

/**
 * Renders a Minecraft texture for a given ID (e.g. "minecraft:spruce_planks").
 * Walks block/ → item/ → entity/ candidates, hides if none load.
 */
export default function McIdTexture({
  id,
  size = 20,
  alt,
  className,
}: McIdTextureProps) {
  const candidates = mcIdToTextureCandidates(id);
  const [index, setIndex] = useState(0);
  const [failed, setFailed] = useState(false);

  if (failed) return null;

  return (
    <PixelIcon
      src={`/minecraft/${candidates[index]}`}
      alt={alt ?? id}
      size={size}
      className={className}
      onError={() => {
        if (index + 1 < candidates.length) {
          setIndex(index + 1);
        } else {
          setFailed(true);
        }
      }}
    />
  );
}
