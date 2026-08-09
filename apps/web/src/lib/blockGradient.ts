import { colorDistanceLab } from "./colors";

export type LabColour = [number, number, number];

export function findClosestBlockAtRank<T extends { lab: LabColour }>(
  targetLab: LabColour,
  blocks: readonly T[],
  rank: number,
): T {
  if (blocks.length === 0) {
    throw new Error("At least one block is required for gradient matching");
  }

  const safeRank = Math.min(
    Math.max(0, Math.floor(rank)),
    blocks.length - 1,
  );
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
      if (closest.length <= safeRank) closest.push(candidate);
    } else {
      closest.splice(insertAt, 0, candidate);
      if (closest.length > safeRank + 1) closest.pop();
    }
  }

  return closest[safeRank]?.block ?? closest[closest.length - 1].block;
}
