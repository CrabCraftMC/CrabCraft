import { hexToRgb, srgbToOklab } from "@/lib/colors";

export const MIN_PIXEL_ART_DETAIL = 16;
export const MAX_PIXEL_ART_DETAIL = 128;
export const PIXEL_ART_ALPHA_THRESHOLD = 128;

export interface PixelArtBlock {
  id: string;
  name: string;
  color: string;
  texture: string;
}

export interface PreparedPixelArtBlock extends PixelArtBlock {
  lab: [number, number, number];
}

export interface PixelArtResult {
  width: number;
  height: number;
  cells: Array<PreparedPixelArtBlock | null>;
  blockCount: number;
  airCount: number;
  uniqueBlocks: number;
}

export function fitGridDimensions(
  sourceWidth: number,
  sourceHeight: number,
  requestedLongestSide: number,
) {
  const longestSide = Math.round(
    Math.min(
      MAX_PIXEL_ART_DETAIL,
      Math.max(MIN_PIXEL_ART_DETAIL, requestedLongestSide),
    ),
  );

  if (sourceWidth >= sourceHeight) {
    return {
      width: longestSide,
      height: Math.max(1, Math.round((sourceHeight / sourceWidth) * longestSide)),
    };
  }

  return {
    width: Math.max(1, Math.round((sourceWidth / sourceHeight) * longestSide)),
    height: longestSide,
  };
}

export function prepareBlockPalette(
  palette: readonly PixelArtBlock[],
): PreparedPixelArtBlock[] {
  return palette.map((block) => ({
    ...block,
    lab: srgbToOklab(...hexToRgb(block.color)),
  }));
}

export function findNearestBlock(
  r: number,
  g: number,
  b: number,
  palette: readonly PreparedPixelArtBlock[],
) {
  const firstBlock = palette[0];
  if (!firstBlock) {
    throw new Error("A block palette is required to generate pixel art");
  }

  const target = srgbToOklab(r, g, b);
  let nearest = firstBlock;
  let shortestDistance = Number.POSITIVE_INFINITY;

  for (const block of palette) {
    const distance =
      (target[0] - block.lab[0]) ** 2 +
      (target[1] - block.lab[1]) ** 2 +
      (target[2] - block.lab[2]) ** 2;

    if (distance < shortestDistance) {
      shortestDistance = distance;
      nearest = block;
    }
  }

  return nearest;
}

export function mapPixelsToBlocks(
  rgba: Uint8ClampedArray,
  width: number,
  height: number,
  palette: readonly PreparedPixelArtBlock[],
  alphaThreshold = PIXEL_ART_ALPHA_THRESHOLD,
): PixelArtResult {
  if (rgba.length !== width * height * 4) {
    throw new Error("Pixel data does not match the requested grid dimensions");
  }
  if (palette.length === 0) {
    throw new Error("A block palette is required to generate pixel art");
  }

  const colorCache = new Map<number, PreparedPixelArtBlock>();
  const usedBlocks = new Set<string>();
  const cells: Array<PreparedPixelArtBlock | null> = [];
  let airCount = 0;

  for (let index = 0; index < rgba.length; index += 4) {
    if (rgba[index + 3] < alphaThreshold) {
      cells.push(null);
      airCount++;
      continue;
    }

    const r = rgba[index];
    const g = rgba[index + 1];
    const b = rgba[index + 2];
    const colorKey = (r << 16) | (g << 8) | b;
    let block = colorCache.get(colorKey);

    if (!block) {
      block = findNearestBlock(r, g, b, palette);
      colorCache.set(colorKey, block);
    }

    cells.push(block);
    usedBlocks.add(block.id);
  }

  return {
    width,
    height,
    cells,
    blockCount: cells.length - airCount,
    airCount,
    uniqueBlocks: usedBlocks.size,
  };
}

export function makePixelArtFilename(
  originalName: string,
  width: number,
  height: number,
) {
  const stem = originalName
    .replace(/\.[^/.]+$/, "")
    .trim()
    .replace(/[^a-z0-9]+/gi, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase();

  return `${stem || "image"}-${width}x${height}-block-art.png`;
}
