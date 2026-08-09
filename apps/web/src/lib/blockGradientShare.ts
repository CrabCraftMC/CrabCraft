import blocks from "@/data/blocks.json";
import {
  isBlockGradientPresetId,
  type BlockGradientPresetId,
} from "@/lib/blockGradientPresets";

export const BLOCK_GRADIENT_SHARE_VERSION = 1;
export const BLOCK_GRADIENT_SHARE_ID_PATTERN = /^[A-Za-z0-9_-]{16}$/;

export type SharedGradientEndpoint = {
  mode: "color" | "block";
  color: string;
  blockId: string | null;
};

export type BlockGradientShareState = {
  start: SharedGradientEndpoint;
  end: SharedGradientEndpoint;
  steps: number;
  randomness: number;
  gradientLength: number;
  paletteOption: number;
  blockPresets: BlockGradientPresetId[];
  excludedIds: string[];
};

const HEX_COLOUR = /^#[0-9a-f]{6}$/i;
const BLOCKS_BY_ID = new Map(blocks.map((block) => [block.id, block]));

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function integerInRange(
  value: unknown,
  minimum: number,
  maximum: number,
): value is number {
  return Number.isInteger(value) && Number(value) >= minimum && Number(value) <= maximum;
}

function normaliseEndpoint(value: unknown): SharedGradientEndpoint | null {
  if (!isRecord(value) || !HEX_COLOUR.test(String(value.color ?? ""))) {
    return null;
  }

  if (value.mode === "color") {
    return {
      mode: "color",
      color: String(value.color).toUpperCase(),
      blockId: null,
    };
  }

  if (value.mode !== "block" || typeof value.blockId !== "string") {
    return null;
  }

  const block = BLOCKS_BY_ID.get(value.blockId);
  if (!block) return null;
  return {
    mode: "block",
    color: block.color.toUpperCase(),
    blockId: block.id,
  };
}

export function normaliseBlockGradientShareState(
  value: unknown,
): BlockGradientShareState | null {
  if (!isRecord(value)) return null;

  const start = normaliseEndpoint(value.start);
  const end = normaliseEndpoint(value.end);
  if (
    !start ||
    !end ||
    !integerInRange(value.steps, 3, 22) ||
    !integerInRange(value.randomness, 0, 100) ||
    !integerInRange(value.gradientLength, 3, 30) ||
    !integerInRange(value.paletteOption, 0, 4) ||
    !Array.isArray(value.blockPresets) ||
    !Array.isArray(value.excludedIds)
  ) {
    return null;
  }

  const blockPresets = [...new Set(value.blockPresets)].filter(
    (id): id is BlockGradientPresetId =>
      isBlockGradientPresetId(id) && id !== "all",
  );
  if (blockPresets.length !== value.blockPresets.length) return null;

  const endpointIds = new Set([start.blockId, end.blockId]);
  const excludedIds = [...new Set(value.excludedIds)].filter(
    (id): id is string =>
      typeof id === "string" &&
      BLOCKS_BY_ID.has(id) &&
      !endpointIds.has(id),
  );
  if (excludedIds.length !== value.excludedIds.length) return null;

  return {
    start,
    end,
    steps: value.steps,
    randomness: value.randomness,
    gradientLength: value.gradientLength,
    paletteOption: value.paletteOption,
    blockPresets,
    excludedIds,
  };
}
