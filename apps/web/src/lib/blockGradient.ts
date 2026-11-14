import { colorDistanceLab, interpolateOklchValues } from "./colors";

export type LabColour = [number, number, number];
export const MAX_PALETTE_OPTIONS = 5;

export function findClosestBlocks<T extends { lab: LabColour }>(
  targetLab: LabColour,
  blocks: readonly T[],
  count: number,
): T[] {
  if (blocks.length === 0) {
    throw new Error("At least one block is required for gradient matching");
  }

  const limit = Math.min(Math.max(1, Math.floor(count)), blocks.length);
  const closest: Array<{ block: T; distance: number }> = [];

  for (const block of blocks) {
    const candidate = {
      block,
      distance: colorDistanceLab(targetLab, block.lab),
    };
    const insertAt = closest.findIndex(
      ({ distance }) => candidate.distance < distance,
    );

    if (insertAt === -1) {
      if (closest.length < limit) closest.push(candidate);
    } else {
      closest.splice(insertAt, 0, candidate);
      if (closest.length > limit) closest.pop();
    }
  }

  return closest.map(({ block }) => block);
}

export function findClosestBlockAtRank<T extends { lab: LabColour }>(
  targetLab: LabColour,
  blocks: readonly T[],
  rank: number,
): T {
  const closest = findClosestBlocks(targetLab, blocks, Math.max(0, Math.floor(rank)) + 1);
  return closest[closest.length - 1];
}

export function generateGradientOptions<T extends { id: string; lab: LabColour }>(
  startLab: LabColour,
  endLab: LabColour,
  blocks: readonly T[],
  startBlock?: T,
  endBlock?: T,
): T[][] {
  // Calculate every ranked match in one palette scan per sample.
  const samples = Array.from({ length: 100 }, (_, i) => findClosestBlocks(
    interpolateOklchValues(startLab, endLab, i / 99), blocks, MAX_PALETTE_OPTIONS,
  ));
  const options: T[][] = [];
  const signatures = new Set<string>();
  for (let rank = 0; rank < Math.min(MAX_PALETTE_OPTIONS, blocks.length); rank++) {
    const oversampled = samples.map((matches) => matches[rank]);
    const sampled = oversampled.filter((block, i) => i === 0 || block.id !== oversampled[i - 1].id);
    const firstBlock = startBlock ?? sampled[0];
    const lastBlock = endBlock ?? sampled[sampled.length - 1];
    const usedIds = new Set([firstBlock.id, lastBlock.id]);
    const middleBlocks = sampled.slice(1, -1).filter((block) => {
      if (usedIds.has(block.id)) return false;
      usedIds.add(block.id);
      return true;
    });
    const unique = [firstBlock, ...middleBlocks, ...(lastBlock.id === firstBlock.id ? [] : [lastBlock])];
    const signature = unique.map((block) => block.id).join("|");
    if (!signatures.has(signature)) {
      signatures.add(signature);
      options.push(unique);
    }
  }
  return options;
}

export function selectGradientBlocks<T extends { lab: LabColour; textureContrast: number }>(
  unique: T[],
  steps: number,
  startLab: LabColour,
  endLab: LabColour,
): T[] {
  if (unique.length <= steps) return unique;

  const baseline = Array.from({ length: steps }, (_, i) =>
    unique[Math.round(i / (steps - 1) * (unique.length - 1))]
  );
  const maxJump = Math.max(...baseline.slice(1).map((block, i) =>
    colorDistanceLab(block.lab, baseline[i].lab)
  ));
  const distances = unique.map((block, i) =>
    unique.slice(0, i).map((previous) => colorDistanceLab(previous.lab, block.lab))
  );

  // Fit evenly spaced target colours while penalising abrupt changes in texture
  // contrast. Keep the old selection's largest colour jump as a hard limit.
  // Endpoints and the number of blocks stay fixed.
  const parents = Array.from({ length: steps }, () => new Int32Array(unique.length).fill(-1));
  let costs = new Float64Array(unique.length).fill(Infinity);
  costs[0] = 0;
  for (let slot = 1; slot < steps; slot++) {
    const target = interpolateOklchValues(startLab, endLab, slot / (steps - 1));
    const next = new Float64Array(unique.length).fill(Infinity);
    const first = slot === steps - 1 ? unique.length - 1 : slot;
    const last = unique.length - (steps - slot);
    for (let i = first; i <= last; i++) {
      let best = Infinity;
      for (let j = slot - 1; j < i; j++) {
        const textureChange = unique[i].textureContrast - unique[j].textureContrast;
        const cost = costs[j] + textureChange ** 2;
        if (distances[i][j] <= maxJump && cost < best) {
          best = cost;
          parents[slot][i] = j;
        }
      }
      next[i] = best + colorDistanceLab(unique[i].lab, target);
    }
    costs = next;
  }

  // The baseline itself is always a valid path through these candidates.
  const result = new Array<T>(steps);
  let index = unique.length - 1;
  for (let slot = steps - 1; slot >= 0; slot--) {
    result[slot] = unique[index];
    index = parents[slot][index];
  }
  return result;
}
