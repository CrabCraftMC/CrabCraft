export const HALLOWEEN_MOBS = ["zombie", "skeleton", "spider", "creeper", "witch"] as const;
export type HalloweenAction = typeof HALLOWEEN_MOBS[number] | "death" | "trick_or_treat" | "back_from_the_dead";

export interface HalloweenProgress {
  hunt_mask: number;
  trick_or_treat: boolean;
  back_from_the_dead: boolean;
}

export function advanceHalloweenProgress(progress: HalloweenProgress, action: HalloweenAction): HalloweenProgress {
  const next = { ...progress };
  if (action === "death") {
    // A completed challenge is permanent; only an unfinished hunt resets.
    if (next.hunt_mask !== 31) next.hunt_mask = 0;
  } else if (action === "trick_or_treat" || action === "back_from_the_dead") {
    next[action] = true;
  } else {
    next.hunt_mask |= 1 << HALLOWEEN_MOBS.indexOf(action);
  }
  return next;
}

export function isHalloweenComplete(progress: HalloweenProgress): boolean {
  return progress.hunt_mask === 31 && progress.trick_or_treat && progress.back_from_the_dead;
}

export function isNewerStreamId(candidate: string, previous: string): boolean {
  const [time, sequence] = candidate.split("-").map(BigInt);
  const [oldTime, oldSequence] = previous.split("-").map(BigInt);
  return time > oldTime || (time === oldTime && sequence > oldSequence);
}

export function parseHalloweenAction(fields: Record<string, string>) {
  const action = fields.action;
  const occurredAt = Number(fields.occurred_at);
  if (!fields.event_id || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(fields.minecraft_uuid ?? "")
    || !Number.isSafeInteger(occurredAt) || occurredAt <= 0
    || ![...HALLOWEEN_MOBS, "death", "trick_or_treat", "back_from_the_dead"].includes(action)) return null;
  return {
    eventId: fields.event_id,
    minecraftUuid: fields.minecraft_uuid.toLowerCase(),
    action: action as HalloweenAction,
    occurredAt,
  };
}
