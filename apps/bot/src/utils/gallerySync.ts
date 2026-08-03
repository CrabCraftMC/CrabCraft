import {
  AttachmentFlags,
  ChannelFlags,
  ChannelType,
  type AnyThreadChannel,
  type Attachment,
  type Channel,
  type Client,
  type ForumChannel,
  type MediaChannel,
  type Message,
} from "discord.js";
import config, { type GalleryChannelConfig } from "./config.js";
import {
  GALLERY_RECONCILE_INTERVAL_MS,
  GALLERY_STORAGE_WRITE_RETRY_DELAYS_MS,
  GALLERY_SYNC_CONCURRENCY,
} from "./constants.js";
import logger from "./logger.js";
import {
  isUnknownChannelError,
} from "./discordErrors.js";
import * as appDb from "./appDb.js";
import {
  getGalleryStorage,
  type GalleryStorage,
} from "./galleryStorage.js";
import { startGalleryStorageDeletionProcessor } from "./galleryDeletionProcessor.js";
import {
  buildGalleryStorageKey,
  inferGalleryImageContentType,
  validateGalleryStorageAttachment,
} from "./galleryStorageHelpers.js";
import {
  findGalleryChannelConfig,
  resolveAppliedGalleryTags,
} from "./galleryHelpers.js";
import {
  buildGalleryPostContentHash,
  buildGalleryTagsHash,
} from "./galleryHashes.js";
import {
  persistStoredGalleryImages,
  queueGalleryStorageWrite,
  storeGalleryImages,
} from "./galleryImageUploads.js";
import type {
  GalleryPostSyncInput,
  GalleryTagSyncInput,
} from "./galleryTypes.js";
import { fetchGalleryStarterMessage } from "./galleryStarter.js";
import { getGalleryReactionSnapshot } from "./galleryReactions.js";

type GalleryParentChannel = ForumChannel | MediaChannel;
type GallerySyncResult =
  | "synced"
  | "stale"
  | "deleted-starter"
  | "no-images";

export interface GalleryReconcileStats {
  channelsScanned: number;
  postsDiscovered: number;
  postsSynced: number;
  deletedStarters: number;
  postsWithoutImages: number;
  staleSnapshots: number;
  failures: number;
}

export interface GalleryReconcileOptions {
  dryRun?: boolean;
  deleteAbsentPosts?: boolean;
  seasonIds?: ReadonlySet<string>;
  reason?: string;
}

const pendingThreadOperations = new Map<string, Promise<unknown>>();
let pendingRevisionAllocation: Promise<unknown> = Promise.resolve();

function unixSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function allocateGallerySyncRevision(): Promise<number> {
  const next = pendingRevisionAllocation
    .catch(() => undefined)
    .then(() => appDb.allocateGallerySyncRevision());
  pendingRevisionAllocation = next;
  return next;
}

function beginSyncRevision(revision?: number): Promise<number> {
  const pending = revision === undefined
    ? allocateGallerySyncRevision()
    : Promise.resolve(revision);
  // The operation queue may still be draining an earlier item when allocation
  // fails. Attach a handler now, then let the queued await surface the error.
  void pending.catch(() => undefined);
  return pending;
}

export function getGalleryChannelConfig(
  channelId: string | null,
): GalleryChannelConfig | null {
  if (config.GALLERY_CONFIGURATION_ERRORS.length > 0) return null;
  return findGalleryChannelConfig(config.GALLERY_CHANNELS, channelId);
}

export function isGalleryParentChannel(
  channel: Channel | null,
): channel is GalleryParentChannel {
  return (
    channel?.type === ChannelType.GuildForum ||
    channel?.type === ChannelType.GuildMedia
  );
}

export function isConfiguredGalleryThread(thread: AnyThreadChannel): boolean {
  return getGalleryChannelConfig(thread.parentId) !== null;
}

function tagDataFromParent(parent: GalleryParentChannel): GalleryTagSyncInput[] {
  return parent.availableTags.map((tag, position) => ({
    discordTagId: tag.id,
    name: tag.name,
    emojiId: tag.emoji?.id ?? null,
    emojiName: tag.emoji?.name ?? null,
    position,
    moderated: tag.moderated,
  }));
}

export async function syncGalleryChannelTags(
  parent: GalleryParentChannel,
  revision?: number,
  syncedAt = unixSeconds(),
): Promise<GalleryTagSyncInput[]> {
  if (!getGalleryChannelConfig(parent.id)) return [];
  const syncRevision = revision ?? await allocateGallerySyncRevision();
  const fetchedParent = await parent.client.channels.fetch(parent.id, {
    force: true,
  });
  if (!isGalleryParentChannel(fetchedParent)) {
    throw new Error(`Gallery parent ${parent.id} is unavailable.`);
  }
  parent = fetchedParent;
  const tags = tagDataFromParent(parent);
  await appDb.replaceGalleryChannelTags(
    parent.id,
    tags,
    buildGalleryTagsHash(tags),
    syncedAt,
    syncRevision,
  );
  return tags;
}

function isSyntheticMediaThumbnail(attachment: Attachment): boolean {
  // This represents the media-channel grid thumbnail, not an image attached
  // to the starter message.
  return attachment.flags.has(AttachmentFlags.IsThumbnail);
}

export function getGalleryImageAttachments(message: Message): Attachment[] {
  return [...message.attachments.values()].filter((attachment) => {
    if (isSyntheticMediaThumbnail(attachment)) return false;
    return (
      inferGalleryImageContentType(attachment.contentType, attachment.name) !== null
    );
  });
}

async function resolveGalleryParent(
  thread: AnyThreadChannel,
  suppliedParent?: GalleryParentChannel,
): Promise<GalleryParentChannel | null> {
  if (suppliedParent?.id === thread.parentId) return suppliedParent;
  if (!thread.parentId) return null;

  const parent = await thread.client.channels
    .fetch(thread.parentId, { force: true })
    .catch(() => null);
  return isGalleryParentChannel(parent) ? parent : null;
}

async function syncGalleryThreadNow(
  thread: AnyThreadChannel,
  options: {
    parent?: GalleryParentChannel;
    retryStarter?: boolean;
    dryRun?: boolean;
    storage?: GalleryStorage;
    revision?: number;
  } = {},
): Promise<GallerySyncResult> {
  let revision = options.revision ?? await allocateGallerySyncRevision();
  let storageRetry = 0;

  while (true) {
    const fetchedThread = await thread.client.channels.fetch(thread.id, {
      force: true,
    });
    if (!fetchedThread?.isThread()) {
      throw new Error(`Gallery thread ${thread.id} is unavailable.`);
    }
    thread = fetchedThread;
    const mapping = getGalleryChannelConfig(thread.parentId);
    if (!mapping) {
      throw new Error(`Thread ${thread.id} is not in a gallery channel.`);
    }

    const parent = await resolveGalleryParent(
      thread,
      storageRetry === 0 ? options.parent : undefined,
    );
    if (!parent) {
      throw new Error(`Gallery parent ${thread.parentId} is unavailable.`);
    }

    const availableTags = tagDataFromParent(parent);
    const starter = await fetchGalleryStarterMessage(
      thread,
      options.retryStarter ?? false,
    );
    if (!starter) {
      if (!options.dryRun) {
        await appDb.markGalleryPostDeleted(thread.id, unixSeconds(), revision);
      }
      return "deleted-starter";
    }

    const attachments = getGalleryImageAttachments(starter);
    if (attachments.length === 0) {
      if (!options.dryRun) {
        await appDb.markGalleryPostDeleted(
          thread.id,
          unixSeconds(),
          revision,
        );
      }
      return "no-images";
    }

    const storageKeys = attachments.map((attachment) => {
      const contentType = validateGalleryStorageAttachment({
        id: attachment.id,
        url: attachment.url,
        filename: attachment.name,
        contentType: attachment.contentType,
        size: attachment.size,
        width: attachment.width,
        height: attachment.height,
      });
      return buildGalleryStorageKey(
        mapping.seasonId,
        thread.id,
        attachment.id,
        contentType,
      );
    });

    if (options.dryRun) return "synced";

    const storage = options.storage ?? getGalleryStorage();
    const storedMedia = storageKeys.map((storageKey) => ({
      storageKey,
      publicUrl: storage.getPublicUrl(storageKey),
    }));
    const outcome = await queueGalleryStorageWrite(async () => {
      // Fence deterministic keys before the first object-storage upload request.
      // The durable reservations also recover uploads if this process crashes
      // before the post snapshot can cancel them in the accepted upsert.
      const preparation = await appDb.prepareGalleryStorageWrites(
        storedMedia,
        unixSeconds(),
      );
      if (preparation.status === "storage-claimed") return preparation;

      const syncedAt = unixSeconds();
      const images = await storeGalleryImages(
        storage,
        mapping.seasonId,
        thread.id,
        attachments,
        syncedAt,
      );
      const accepted = await persistStoredGalleryImages(
        images,
        syncedAt,
        thread.id,
        async () => {
          const content = starter.content.trim();
          const snapshot = {
            threadId: thread.id,
            channelId: parent.id,
            seasonId: mapping.seasonId,
            title: thread.name,
            content: content.length > 0 ? content : null,
            authorDiscordId: starter.author.id,
            authorDiscordUsername: starter.author.username,
            authorDisplayName:
              starter.member?.displayName ?? starter.author.globalName ?? null,
            authorWebhookId: starter.webhookId,
            sourceUrl: thread.url,
            postedAt: Math.floor(starter.createdTimestamp / 1000),
            editedAt:
              starter.editedTimestamp === null
                ? null
                : Math.floor(starter.editedTimestamp / 1000),
            archived: thread.archived ?? false,
            locked: thread.locked ?? false,
            pinned: thread.flags.has(ChannelFlags.Pinned),
            tags: resolveAppliedGalleryTags(
              thread.appliedTags,
              availableTags,
            ),
            images,
            reactions: getGalleryReactionSnapshot(starter),
          };
          const post: GalleryPostSyncInput = {
            ...snapshot,
            revision,
            contentHash: buildGalleryPostContentHash(snapshot),
            syncedAt,
          };
          const upserted = await appDb.upsertGalleryPost(post);
          if (!upserted) {
            await appDb.enqueueUnreferencedGalleryStorageDeletions(
              images.map(({ storageKey, publicUrl }) => ({
                storageKey,
                publicUrl,
              })),
              syncedAt,
            );
          }
          return upserted;
        },
      );
      return { status: accepted ? "synced" : "stale" } as const;
    });

    if (outcome.status !== "storage-claimed") return outcome.status;

    const retryDelay = GALLERY_STORAGE_WRITE_RETRY_DELAYS_MS[storageRetry];
    if (retryDelay === undefined) {
      throw new Error(
        `Gallery storage for thread ${thread.id} remained claimed for deletion after bounded retries (lease ${outcome.retryAfter}).`,
      );
    }
    storageRetry += 1;
    logger.warn(
      `[gallery] Waiting ${retryDelay}ms for storage cleanup before refetching thread ${thread.id}.`,
    );
    await delay(retryDelay);
    revision = await allocateGallerySyncRevision();
  }
}

function queueThreadOperation<T>(
  threadId: string,
  operation: () => Promise<T>,
): Promise<T> {
  const previous = pendingThreadOperations.get(threadId) ?? Promise.resolve();
  const next = previous.catch(() => undefined).then(operation);
  pendingThreadOperations.set(threadId, next);
  void next
    .then(
      () => undefined,
      () => undefined,
    )
    .then(() => {
      if (pendingThreadOperations.get(threadId) === next) {
        pendingThreadOperations.delete(threadId);
      }
    });
  return next;
}

export function queueGalleryThreadSync(
  thread: AnyThreadChannel,
  options: {
    parent?: GalleryParentChannel;
    retryStarter?: boolean;
    dryRun?: boolean;
    storage?: GalleryStorage;
    revision?: number;
  } = {},
): Promise<GallerySyncResult> {
  const revision = beginSyncRevision(options.revision);
  return queueThreadOperation(thread.id, async () =>
    syncGalleryThreadNow(thread, { ...options, revision: await revision }),
  );
}

export function queueGalleryReactionSync(
  thread: AnyThreadChannel,
): Promise<boolean> {
  const revision = beginSyncRevision();
  return queueThreadOperation(thread.id, async () => {
    const fetchedThread = await thread.client.channels.fetch(thread.id, {
      force: true,
    });
    if (!fetchedThread?.isThread() || !isConfiguredGalleryThread(fetchedThread)) {
      return false;
    }
    const starter = await fetchGalleryStarterMessage(fetchedThread, false);
    if (!starter) return false;
    return appDb.replaceGalleryPostReactions(
      fetchedThread.id,
      getGalleryReactionSnapshot(starter),
      await revision,
    );
  });
}

export function queueGalleryPostDeletion(
  threadId: string,
  deletedAt = unixSeconds(),
  revision?: number,
): Promise<boolean> {
  const syncRevision = beginSyncRevision(revision);
  return queueThreadOperation(threadId, async () =>
    appDb.markGalleryPostDeleted(threadId, deletedAt, await syncRevision),
  );
}

async function processWithConcurrency<T>(
  values: readonly T[],
  concurrency: number,
  worker: (value: T) => Promise<void>,
): Promise<void> {
  let nextIndex = 0;
  const run = async () => {
    while (nextIndex < values.length) {
      const index = nextIndex++;
      await worker(values[index]);
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(concurrency, values.length) }, run),
  );
}

function countResult(
  stats: GalleryReconcileStats,
  result: GallerySyncResult,
): void {
  if (result === "synced") stats.postsSynced += 1;
  else if (result === "stale") stats.staleSnapshots += 1;
  else if (result === "deleted-starter") stats.deletedStarters += 1;
  else stats.postsWithoutImages += 1;
}

async function fetchAllGalleryThreads(
  parent: GalleryParentChannel,
): Promise<Map<string, AnyThreadChannel>> {
  const threads = new Map<string, AnyThreadChannel>();
  const active = await parent.threads.fetchActive(false);
  for (const thread of active.threads.values()) {
    if (thread.parentId === parent.id) threads.set(thread.id, thread);
  }

  let before: Date | undefined;
  while (true) {
    const page = await parent.threads.fetchArchived(
      { type: "public", limit: 100, before },
      false,
    );
    for (const thread of page.threads.values()) {
      if (thread.parentId === parent.id) threads.set(thread.id, thread);
    }
    if (!page.hasMore) break;

    const archiveTimestamps = [...page.threads.values()]
      .map((thread) => thread.archiveTimestamp)
      .filter((value): value is number => value !== null);
    if (archiveTimestamps.length === 0) {
      throw new Error(
        `Discord reported more archived threads for ${parent.id} without a pagination timestamp.`,
      );
    }
    const oldest = Math.min(...archiveTimestamps);
    const nextBefore = new Date(oldest);
    if (before && nextBefore >= before) {
      throw new Error(`Archived thread pagination stalled for ${parent.id}.`);
    }
    before = nextBefore;
  }

  // A thread can be unarchived after the first active inventory but before its
  // archived page is fetched. Union a fresh active snapshot so that transition
  // cannot make a live post look absent.
  const refreshedActive = await parent.threads.fetchActive(false);
  for (const thread of refreshedActive.threads.values()) {
    if (thread.parentId === parent.id) threads.set(thread.id, thread);
  }

  return threads;
}

async function reconcileGalleryChannel(
  client: Client<true>,
  mapping: GalleryChannelConfig,
  stats: GalleryReconcileStats,
  options: GalleryReconcileOptions,
  storage: GalleryStorage | undefined,
  scanRevision: number,
): Promise<void> {
  // The database-issued scan revision makes this inventory older than any
  // Gateway event observed after the scan began, across every bot process.
  const scanStartedAt = unixSeconds();
  let channel: Awaited<ReturnType<typeof client.channels.fetch>>;
  try {
    channel = await client.channels.fetch(mapping.channelId, { force: true });
  } catch (error) {
    if (!isUnknownChannelError(error)) throw error;
    if (options.dryRun) {
      logger.warn(
        `[gallery] Season ${mapping.seasonId} channel ${mapping.channelId} no longer exists; a live reconciliation would remove it.`,
      );
    } else {
      await appDb.markGalleryChannelDeleted(
        mapping.channelId,
        scanStartedAt,
        scanRevision,
      );
    }
    return;
  }
  if (!isGalleryParentChannel(channel)) {
    throw new Error(
      `Configured gallery channel ${mapping.channelId} is not a Forum or Media channel.`,
    );
  }

  const availableTags = tagDataFromParent(channel);
  if (!options.dryRun) {
    await appDb.replaceGalleryChannelTags(
      channel.id,
      availableTags,
      buildGalleryTagsHash(availableTags),
      scanStartedAt,
      scanRevision,
    );
  }

  const threads = await fetchAllGalleryThreads(channel);
  stats.channelsScanned += 1;
  stats.postsDiscovered += threads.size;

  await processWithConcurrency(
    [...threads.values()],
    GALLERY_SYNC_CONCURRENCY,
    async (thread) => {
      try {
        const result = await queueGalleryThreadSync(thread, {
          parent: channel,
          dryRun: options.dryRun,
          storage,
          revision: scanRevision,
        });
        countResult(stats, result);
      } catch (error) {
        stats.failures += 1;
        logger.error(`[gallery] Failed to sync thread ${thread.id}: ${String(error)}`);
      }
    },
  );

  if (!options.dryRun && options.deleteAbsentPosts !== false) {
    await appDb.markGalleryPostsDeletedExcept(
      channel.id,
      [...threads.keys()],
      scanStartedAt,
      scanRevision,
    );
  }
}

function emptyStats(): GalleryReconcileStats {
  return {
    channelsScanned: 0,
    postsDiscovered: 0,
    postsSynced: 0,
    deletedStarters: 0,
    postsWithoutImages: 0,
    staleSnapshots: 0,
    failures: 0,
  };
}

export async function reconcileGallery(
  client: Client<true>,
  options: GalleryReconcileOptions = {},
): Promise<GalleryReconcileStats> {
  if (config.GALLERY_CONFIGURATION_ERRORS.length > 0) {
    throw new Error(
      `Invalid Gallery configuration: ${config.GALLERY_CONFIGURATION_ERRORS.join(" ")}`,
    );
  }
  const mappings = config.GALLERY_CHANNELS.filter(
    (mapping) => !options.seasonIds || options.seasonIds.has(mapping.seasonId),
  );
  const stats = emptyStats();
  if (mappings.length === 0) return stats;

  // Dry runs inspect Discord only. Every mutating path requires durable storage
  // to be completely configured; there is deliberately no Discord-CDN fallback.
  const storage = options.dryRun ? undefined : getGalleryStorage();
  const scanRevision = options.dryRun
    ? 0
    : await allocateGallerySyncRevision();
  for (const mapping of mappings) {
    const failuresBeforeChannel = stats.failures;
    try {
      await reconcileGalleryChannel(
        client,
        mapping,
        stats,
        options,
        storage,
        scanRevision,
      );
    } catch (error) {
      stats.failures += 1;
      logger.error(
        `[gallery] Failed to reconcile Season ${mapping.seasonId} channel ${mapping.channelId}: ${String(error)}`,
      );
    }
    // Failure accounting is global for reporting but absent-row deletion is
    // guarded within each channel. Keep this variable explicit for log clarity.
    const channelFailures = stats.failures - failuresBeforeChannel;
    if (channelFailures > 0) {
      logger.warn(
        `[gallery] Season ${mapping.seasonId} reconciliation completed with ${channelFailures} failure(s).`,
      );
    }
  }

  logger.info(
    `[gallery] ${options.reason ?? (options.dryRun ? "dry run" : "reconciliation")} complete: ${stats.channelsScanned} channel(s), ${stats.postsDiscovered} discovered, ${stats.postsSynced} ${options.dryRun ? "eligible" : "synced"}, ${stats.staleSnapshots} stale snapshot(s), ${stats.deletedStarters} deleted starter(s), ${stats.postsWithoutImages} without images, ${stats.failures} failure(s)`,
  );
  return stats;
}

export function startGallerySync(client: Client<true>): void {
  if (config.GALLERY_CONFIGURATION_ERRORS.length > 0) {
    logger.error(
      `[gallery] Synchronisation disabled: ${config.GALLERY_CONFIGURATION_ERRORS.join(" ")}`,
    );
    return;
  }
  if (config.GALLERY_CHANNELS.length === 0) {
    logger.info("[gallery] No gallery channels configured; synchronisation disabled.");
    return;
  }

  let storage: GalleryStorage;
  try {
    storage = getGalleryStorage();
  } catch (error) {
    logger.error(`[gallery] Synchronisation disabled: ${String(error)}`);
    return;
  }

  startGalleryStorageDeletionProcessor(storage);

  let running = false;
  const run = async () => {
    if (running) return;
    running = true;
    try {
      await reconcileGallery(client, { reason: "periodic reconciliation" });
    } catch (error) {
      logger.error(`[gallery] Reconciliation failed: ${String(error)}`);
    } finally {
      running = false;
    }
  };

  void run();
  setInterval(run, GALLERY_RECONCILE_INTERVAL_MS);
}
