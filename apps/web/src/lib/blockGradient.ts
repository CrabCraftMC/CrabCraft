import { colorDistanceLab } from "./colors";

export type LabColour = [number, number, number];

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
  const closest = findClosestBlocks(targetLab, blocks, rank + 1);
  return closest[closest.length - 1];
}
