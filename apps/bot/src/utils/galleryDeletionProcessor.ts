import type { GalleryStorageDeletionClaim } from "./appDb.js";
import * as appDb from "./appDb.js";
import {
  GALLERY_STORAGE_DELETE_BATCH_SIZE,
  GALLERY_STORAGE_DELETE_INTERVAL_MS,
  GALLERY_STORAGE_DELETE_LEASE_SECONDS,
} from "./constants.js";
import type { GalleryStorage } from "./galleryStorage.js";
import logger from "./logger.js";

export interface GalleryStorageDeletionQueue {
  claim(
    now: number,
    limit: number,
    leaseUntil: number,
  ): Promise<GalleryStorageDeletionClaim[]>;
  execute(
    claim: GalleryStorageDeletionClaim,
    deleteObject: () => Promise<void>,
  ): Promise<"deleted" | "stale-claim" | "referenced">;
  fail(
    claim: GalleryStorageDeletionClaim,
    attemptedAt: number,
    error: string,
  ): Promise<boolean>;
}

export interface GalleryStorageDeletionStats {
  attempted: number;
  completed: number;
  skipped: number;
  failed: number;
}

function unixSeconds(): number {
  return Math.floor(Date.now() / 1_000);
}

export async function processDueGalleryStorageDeletions(
  queue: GalleryStorageDeletionQueue,
  storage: Pick<GalleryStorage, "delete">,
  options: {
    clock?: () => number;
    limit?: number;
    leaseSeconds?: number;
  } = {},
): Promise<GalleryStorageDeletionStats> {
  const clock = options.clock ?? unixSeconds;
  const now = clock();
  const limit = options.limit ?? GALLERY_STORAGE_DELETE_BATCH_SIZE;
  const leaseSeconds =
    options.leaseSeconds ?? GALLERY_STORAGE_DELETE_LEASE_SECONDS;
  if (!Number.isSafeInteger(now) || now <= 0) {
    throw new Error("Gallery deletion processor requires a valid Unix timestamp.");
  }
  if (!Number.isSafeInteger(limit) || limit <= 0 || limit > 100) {
    throw new Error("Gallery deletion processor limit must be from 1 to 100.");
  }
  if (!Number.isSafeInteger(leaseSeconds) || leaseSeconds <= 0) {
    throw new Error("Gallery deletion processor requires a positive lease.");
  }

  const claims = await queue.claim(now, limit, now + leaseSeconds);
  const stats: GalleryStorageDeletionStats = {
    attempted: claims.length,
    completed: 0,
    skipped: 0,
    failed: 0,
  };

  for (const claim of claims) {
    try {
      const result = await queue.execute(claim, () =>
        storage.delete(claim.storageKey, claim.publicUrl),
      );
      if (result === "deleted") stats.completed += 1;
      else stats.skipped += 1;
    } catch (error) {
      stats.failed += 1;
      await queue.fail(claim, clock(), String(error).slice(0, 4_000));
    }
  }

  return stats;
}

const databaseQueue: GalleryStorageDeletionQueue = {
  claim: appDb.claimDueGalleryStorageDeletions,
  execute: appDb.executeGalleryStorageDeletionClaim,
  fail: appDb.recordGalleryStorageDeletionFailure,
};

export function startGalleryStorageDeletionProcessor(
  storage: GalleryStorage,
): void {
  let running = false;
  const run = async () => {
    if (running) return;
    running = true;
    try {
      const stats = await processDueGalleryStorageDeletions(
        databaseQueue,
        storage,
      );
      if (stats.attempted > 0) {
        logger.info(
          `[gallery] Storage cleanup: ${stats.completed} deleted, ${stats.skipped} skipped, ${stats.failed} failed.`,
        );
      }
    } catch (error) {
      logger.error(`[gallery] Storage cleanup failed: ${String(error)}`);
    } finally {
      running = false;
    }
  };

  void run();
  setInterval(run, GALLERY_STORAGE_DELETE_INTERVAL_MS);
}
