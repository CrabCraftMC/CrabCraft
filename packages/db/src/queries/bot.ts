import {
  eq,
  and,
  asc,
  desc,
  sql,
  lte,
  lt,
  ne,
  ilike,
  isNull,
  isNotNull,
  inArray,
  notInArray,
} from "drizzle-orm";
import { db } from "../client";
import {
  players,
  seasons,
  applications,
  streamChannels,
  playerAlts,
  starboardPosts,
  countingState,
  tickets,
  applicationChannels,
  galleryPosts,
  galleryImages,
  galleryReactions,
  galleryTags,
  galleryPostTags,
  galleryPostSyncState,
  galleryChannelSyncState,
  galleryStorageDeletions,
  type TicketCategory,
} from "../schema";

export type { TicketCategory, TicketStatus } from "../schema";

export interface UpsertUserData {
  discordId: string;
  discordUsername: string;
  minecraftUsername?: string;
  minecraftUuid?: string;
}

export interface CreateApplicationData {
  discordId: string;
  discordUsername: string;
  minecraftUsername: string;
  minecraftUuid: string | null;
  ageMet: boolean;
  voiceChat: boolean;
  joinReason?: string;
  favouriteWood?: string;
  season?: string | null;
}

export interface GalleryAppliedTagSyncInput {
  discordTagId: string;
  name: string;
  emojiId: string | null;
  emojiName: string | null;
  position: number;
  moderated: boolean;
}

export interface GalleryImageSyncInput {
  discordAttachmentId: string;
  storageKey: string;
  publicUrl: string;
  filename: string;
  alt: string | null;
  contentType: string | null;
  size: number;
  width: number | null;
  height: number | null;
  position: number;
}

export interface GalleryReactionSyncInput {
  emojiKey: string;
  emojiId: string | null;
  emojiName: string;
  animated: boolean;
  count: number;
}

export interface GalleryPostSyncInput {
  threadId: string;
  channelId: string;
  seasonId: string;
  title: string;
  content: string | null;
  authorDiscordId: string;
  authorDiscordUsername: string;
  authorDisplayName: string | null;
  authorWebhookId: string | null;
  sourceUrl: string;
  postedAt: number;
  editedAt: number | null;
  archived: boolean;
  locked: boolean;
  pinned: boolean;
  syncedAt: number;
  revision: number;
  contentHash: string;
  tags: GalleryAppliedTagSyncInput[];
  images: GalleryImageSyncInput[];
  reactions: GalleryReactionSyncInput[];
}

export interface GalleryStorageDeletionClaim {
  storageKey: string;
  publicUrl: string;
  attempts: number;
  claimedAt: number;
  leaseUntil: number;
}

export interface GalleryStoredMediaInput {
  storageKey: string;
  publicUrl: string;
}

export type GalleryStorageWritePreparationResult =
  | { status: "ready" }
  | { status: "storage-claimed"; retryAfter: number };

export type GalleryStorageDeletionExecutionResult =
  | "deleted"
  | "stale-claim"
  | "referenced";

const GALLERY_STORAGE_DELETE_GRACE_SECONDS = 5 * 60;
const GALLERY_STORAGE_WRITE_RESERVATION_SECONDS = 30 * 60;
const GALLERY_STORAGE_RETRY_MAX_SECONDS = 60 * 60;

type GalleryTransaction = Parameters<
  Parameters<typeof db.transaction>[0]
>[0];

function assertGalleryRevision(revision: number): void {
  if (!Number.isSafeInteger(revision) || revision <= 0) {
    throw new Error(`Invalid gallery sync revision: ${revision}`);
  }
}

function assertGalleryTimestamp(label: string, value: number): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`Invalid Gallery ${label} timestamp: ${value}`);
  }
}

function gallerySeasonNumber(seasonId: string): number {
  const matches = seasonId.match(/\d+/g);
  const value = matches ? Number(matches[matches.length - 1]) : Number.NaN;
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`Invalid gallery season id: ${seasonId}`);
  }
  return value;
}

function assertGalleryPositions(
  kind: "image" | "tag",
  entries: Array<{ position: number }>,
): void {
  const positions = new Set<number>();
  for (const entry of entries) {
    if (!Number.isSafeInteger(entry.position) || entry.position < 0) {
      throw new Error(`Invalid gallery ${kind} position: ${entry.position}`);
    }
    if (positions.has(entry.position)) {
      throw new Error(`Duplicate gallery ${kind} position: ${entry.position}`);
    }
    positions.add(entry.position);
  }
}

function assertGalleryReactions(
  reactions: GalleryReactionSyncInput[],
): void {
  const keys = new Set<string>();
  for (const reaction of reactions) {
    if (!reaction.emojiKey || keys.has(reaction.emojiKey)) {
      throw new Error(`Invalid or duplicate Gallery reaction key: ${reaction.emojiKey}`);
    }
    if (!reaction.emojiName) {
      throw new Error("Gallery reactions must include an emoji name");
    }
    if (!Number.isSafeInteger(reaction.count) || reaction.count <= 0) {
      throw new Error(`Invalid Gallery reaction count: ${reaction.count}`);
    }
    keys.add(reaction.emojiKey);
  }
}

async function replaceGalleryPostReactionsInTransaction(
  tx: GalleryTransaction,
  threadId: string,
  reactions: GalleryReactionSyncInput[],
  revision: number,
): Promise<boolean> {
  const accepted = await tx
    .update(galleryPosts)
    .set({ reactions_revision: revision })
    .where(
      and(
        eq(galleryPosts.thread_id, threadId),
        lt(galleryPosts.reactions_revision, revision),
      ),
    )
    .returning({ threadId: galleryPosts.thread_id });
  if (accepted.length === 0) return false;

  await tx
    .delete(galleryReactions)
    .where(eq(galleryReactions.post_id, threadId));
  if (reactions.length > 0) {
    await tx.insert(galleryReactions).values(
      reactions.map((reaction) => ({
        post_id: threadId,
        emoji_key: reaction.emojiKey,
        emoji_id: reaction.emojiId,
        emoji_name: reaction.emojiName,
        animated: reaction.animated,
        count: reaction.count,
      })),
    );
  }
  return true;
}

async function lockGalleryChannelSyncState(
  tx: GalleryTransaction,
  channelId: string,
) {
  await tx
    .insert(galleryChannelSyncState)
    .values({ channel_id: channelId })
    .onConflictDoNothing();
  const [state] = await tx
    .select()
    .from(galleryChannelSyncState)
    .where(eq(galleryChannelSyncState.channel_id, channelId))
    .for("update");
  if (!state) {
    throw new Error(`Gallery channel sync state missing for ${channelId}`);
  }
  return state;
}

async function acceptGalleryPostRevisions(
  tx: GalleryTransaction,
  threadIds: string[],
  revision: number,
): Promise<string[]> {
  if (threadIds.length === 0) return [];
  const rows = await tx
    .insert(galleryPostSyncState)
    .values(
      threadIds.map((threadId) => ({
        thread_id: threadId,
        last_revision: revision,
      })),
    )
    .onConflictDoUpdate({
      target: galleryPostSyncState.thread_id,
      set: { last_revision: revision },
      setWhere: lt(galleryPostSyncState.last_revision, revision),
    })
    .returning({ threadId: galleryPostSyncState.thread_id });
  return rows.map((row) => row.threadId);
}

async function lockGalleryStorageKeys(
  tx: GalleryTransaction,
  storageKeys: string[],
): Promise<void> {
  const keys = [...new Set(storageKeys)].sort();
  for (const storageKey of keys) {
    await tx.execute(
      sql`SELECT pg_advisory_xact_lock(hashtextextended(${storageKey}, 0))`,
    );
  }
}

async function lockActiveGalleryStorageClaims(
  tx: GalleryTransaction,
  storageKeys: string[],
): Promise<Array<{ storageKey: string; retryAfter: number }>> {
  if (storageKeys.length === 0) return [];
  return tx
    .select({
      storageKey: galleryStorageDeletions.storage_key,
      retryAfter: galleryStorageDeletions.delete_after,
    })
    .from(galleryStorageDeletions)
    .where(
      and(
        inArray(galleryStorageDeletions.storage_key, storageKeys),
        isNotNull(galleryStorageDeletions.last_attempt_at),
        sql`${galleryStorageDeletions.delete_after} > FLOOR(EXTRACT(EPOCH FROM clock_timestamp()))::integer`,
      ),
    )
    .for("update");
}

/**
 * Reserve deterministic Gallery object keys before consulting or writing S3.
 * Unreferenced keys receive a durable cleanup row, so a process crash after an
 * upload cannot leak the object. A successful post upsert cancels those rows.
 * Once this function owns the key lock, an expired claim is safe to replace:
 * any external delete still in progress would be holding that same lock.
 */
export async function prepareGalleryStorageWrites(
  images: GalleryStoredMediaInput[],
  preparedAt: number,
): Promise<GalleryStorageWritePreparationResult> {
  assertGalleryTimestamp("storage write preparation", preparedAt);
  const uniqueImages = Array.from(
    new Map(images.map((image) => [image.storageKey, image])).values(),
  ).sort((left, right) => left.storageKey.localeCompare(right.storageKey));
  if (uniqueImages.length === 0) return { status: "ready" };
  const storageKeys = uniqueImages.map((image) => image.storageKey);

  return db.transaction(async (tx) => {
    await lockGalleryStorageKeys(tx, storageKeys);
    const claims = await lockActiveGalleryStorageClaims(tx, storageKeys);
    if (claims.length > 0) {
      return {
        status: "storage-claimed" as const,
        retryAfter: Math.max(...claims.map((claim) => claim.retryAfter)),
      };
    }
    await tx
      .delete(galleryStorageDeletions)
      .where(inArray(galleryStorageDeletions.storage_key, storageKeys));

    const referenced = await tx
      .select({ storageKey: galleryImages.storage_key })
      .from(galleryImages)
      .where(inArray(galleryImages.storage_key, storageKeys));
    const referencedKeys = new Set(referenced.map((row) => row.storageKey));
    const reservations = uniqueImages.filter(
      (image) => !referencedKeys.has(image.storageKey),
    );
    if (reservations.length > 0) {
      await tx.insert(galleryStorageDeletions).values(
        reservations.map((image) => ({
          storage_key: image.storageKey,
          public_url: image.publicUrl,
          queued_at: preparedAt,
          delete_after:
            preparedAt + GALLERY_STORAGE_WRITE_RESERVATION_SECONDS,
        })),
      );
    }
    return { status: "ready" as const };
  });
}

async function queueGalleryStorageDeletions(
  tx: GalleryTransaction,
  images: Array<{ storageKey: string; publicUrl: string }>,
  queuedAt: number,
): Promise<void> {
  if (images.length === 0) return;
  await lockGalleryStorageKeys(
    tx,
    images.map((image) => image.storageKey),
  );
  const deleteAfter = queuedAt + GALLERY_STORAGE_DELETE_GRACE_SECONDS;
  await tx
    .insert(galleryStorageDeletions)
    .values(
      images.map((image) => ({
        storage_key: image.storageKey,
        public_url: image.publicUrl,
        queued_at: queuedAt,
        delete_after: deleteAfter,
      })),
    )
    .onConflictDoUpdate({
      target: galleryStorageDeletions.storage_key,
      set: {
        public_url: sql`excluded.public_url`,
        queued_at: sql`LEAST(${galleryStorageDeletions.queued_at}, excluded.queued_at)`,
        delete_after: sql`LEAST(${galleryStorageDeletions.delete_after}, excluded.delete_after)`,
      },
      // An external delete may already be in flight for a claimed row. Leave
      // its fencing values untouched so writers continue to observe it busy.
      setWhere: isNull(galleryStorageDeletions.last_attempt_at),
    });
}

async function queueAndRemoveGalleryImages(
  tx: GalleryTransaction,
  threadIds: string[],
  queuedAt: number,
): Promise<void> {
  if (threadIds.length === 0) return;
  const images = await tx
    .select({
      storageKey: galleryImages.storage_key,
      publicUrl: galleryImages.public_url,
    })
    .from(galleryImages)
    .where(inArray(galleryImages.post_id, threadIds));
  await queueGalleryStorageDeletions(tx, images, queuedAt);
  await tx
    .delete(galleryImages)
    .where(inArray(galleryImages.post_id, threadIds));
}

/** Allocate one total-order revision before fetching any Discord state. */
export async function allocateGallerySyncRevision(): Promise<number> {
  const rows = await db.execute(
    sql`SELECT nextval('gallery_sync_revision_seq') AS revision`,
  );
  const revision = Number(
    (rows as unknown as Array<{ revision: number | string }>)[0]?.revision,
  );
  assertGalleryRevision(revision);
  return revision;
}

/**
 * Persist one complete Discord media post snapshot. Images and applied tags
 * are replaced in the same transaction, so the website never observes a
 * mixture of the old and new Discord state.
 */
export async function upsertGalleryPost(
  data: GalleryPostSyncInput,
): Promise<boolean> {
  const seasonNumber = gallerySeasonNumber(data.seasonId);
  assertGalleryRevision(data.revision);
  assertGalleryTimestamp("posted", data.postedAt);
  assertGalleryTimestamp("sync", data.syncedAt);
  if (data.editedAt !== null) {
    assertGalleryTimestamp("edited", data.editedAt);
  }
  if (data.images.length === 0) {
    throw new Error("Gallery posts must contain at least one image");
  }
  assertGalleryPositions("image", data.images);
  assertGalleryPositions("tag", data.tags);
  assertGalleryReactions(data.reactions);

  return db.transaction(async (tx) => {
    const channelState = await lockGalleryChannelSyncState(tx, data.channelId);
    // Do not compare tags_revision here. Revisions order operations, not the
    // freshness of independent resources: this post was force-fetched and a
    // newer tag rename must not suppress its message edit.
    if (
      channelState.deleted_revision !== null &&
      channelState.deleted_revision >= data.revision
    ) {
      return false;
    }

    const accepted = await acceptGalleryPostRevisions(
      tx,
      [data.threadId],
      data.revision,
    );
    if (accepted.length === 0) return false;

    const previousImages = await tx
      .select({
        storageKey: galleryImages.storage_key,
        publicUrl: galleryImages.public_url,
      })
      .from(galleryImages)
      .where(eq(galleryImages.post_id, data.threadId));
    await lockGalleryStorageKeys(
      tx,
      [
        ...previousImages.map((image) => image.storageKey),
        ...data.images.map((image) => image.storageKey),
      ],
    );
    const currentStorageKeys = new Set(
      data.images.map((image) => image.storageKey),
    );
    await queueGalleryStorageDeletions(
      tx,
      previousImages.filter(
        (image) => !currentStorageKeys.has(image.storageKey),
      ),
      data.syncedAt,
    );
    await tx
      .delete(galleryStorageDeletions)
      .where(
        inArray(
          galleryStorageDeletions.storage_key,
          data.images.map((image) => image.storageKey),
        ),
      );

    const initialContentUpdatedAt = Math.max(
      data.postedAt,
      data.editedAt ?? data.postedAt,
    );
    await tx
      .insert(galleryPosts)
      .values({
        thread_id: data.threadId,
        channel_id: data.channelId,
        season_id: data.seasonId,
        season_number: seasonNumber,
        title: data.title,
        content: data.content,
        author_discord_id: data.authorDiscordId,
        author_discord_username: data.authorDiscordUsername,
        author_display_name:
          data.authorDisplayName ?? data.authorDiscordUsername,
        author_webhook_id: data.authorWebhookId,
        source_url: data.sourceUrl,
        posted_at: data.postedAt,
        edited_at: data.editedAt,
        content_hash: data.contentHash,
        content_updated_at: initialContentUpdatedAt,
        archived: data.archived,
        locked: data.locked,
        pinned: data.pinned,
        published: true,
        published_at: data.postedAt,
        deleted_at: null,
        last_synced_at: data.syncedAt,
      })
      .onConflictDoUpdate({
        target: galleryPosts.thread_id,
        set: {
          channel_id: sql`excluded.channel_id`,
          season_id: sql`excluded.season_id`,
          season_number: sql`excluded.season_number`,
          title: sql`excluded.title`,
          content: sql`excluded.content`,
          author_discord_id: sql`excluded.author_discord_id`,
          author_discord_username: sql`excluded.author_discord_username`,
          author_display_name: sql`excluded.author_display_name`,
          author_webhook_id: sql`excluded.author_webhook_id`,
          source_url: sql`excluded.source_url`,
          posted_at: sql`excluded.posted_at`,
          edited_at: sql`excluded.edited_at`,
          content_hash: sql`excluded.content_hash`,
          content_updated_at: sql`CASE
            WHEN ${galleryPosts.content_hash} IS DISTINCT FROM excluded.content_hash
              OR ${galleryPosts.published} = false
              OR ${galleryPosts.deleted_at} IS NOT NULL
              THEN GREATEST(
                ${galleryPosts.content_updated_at},
                excluded.last_synced_at
              )
            ELSE ${galleryPosts.content_updated_at}
          END`,
          archived: sql`excluded.archived`,
          locked: sql`excluded.locked`,
          pinned: sql`excluded.pinned`,
          published: true,
          deleted_at: null,
          last_synced_at: sql`excluded.last_synced_at`,
        },
      });

    if (data.tags.length > 0) {
      await tx
        .insert(galleryTags)
        .values(
          data.tags.map((tag) => ({
            discord_tag_id: tag.discordTagId,
            channel_id: data.channelId,
            name: tag.name,
            emoji_id: tag.emojiId,
            emoji_name: tag.emojiName,
            moderated: tag.moderated,
            available: true,
            position: tag.position,
            last_synced_at: data.syncedAt,
          })),
        )
        // Applied tags from archived threads can be absent from Discord's
        // current catalogue. Preserve any known snapshot; the complete
        // channel refresh below is authoritative for names and emoji.
        .onConflictDoNothing();
    }

    await tx
      .delete(galleryImages)
      .where(eq(galleryImages.post_id, data.threadId));
    if (data.images.length > 0) {
      await tx.insert(galleryImages).values(
        data.images.map((image) => ({
          discord_attachment_id: image.discordAttachmentId,
          post_id: data.threadId,
          storage_key: image.storageKey,
          public_url: image.publicUrl,
          filename: image.filename,
          alt: image.alt,
          content_type: image.contentType,
          size: image.size,
          width: image.width,
          height: image.height,
          position: image.position,
        })),
      );
    }

    await tx
      .delete(galleryPostTags)
      .where(eq(galleryPostTags.post_id, data.threadId));
    if (data.tags.length > 0) {
      await tx.insert(galleryPostTags).values(
        data.tags.map((tag) => ({
          post_id: data.threadId,
          tag_id: tag.discordTagId,
        })),
      );
    }
    await replaceGalleryPostReactionsInTransaction(
      tx,
      data.threadId,
      data.reactions,
      data.revision,
    );
    return true;
  });
}

/** Replace reaction counts without making an older content snapshot stale. */
export async function replaceGalleryPostReactions(
  threadId: string,
  reactions: GalleryReactionSyncInput[],
  revision: number,
): Promise<boolean> {
  assertGalleryRevision(revision);
  assertGalleryReactions(reactions);
  return db.transaction((tx) =>
    replaceGalleryPostReactionsInTransaction(
      tx,
      threadId,
      reactions,
      revision,
    ),
  );
}

/** Replace the available Discord tag catalogue for one media channel. */
export async function replaceGalleryChannelTags(
  channelId: string,
  tags: GalleryAppliedTagSyncInput[],
  tagsHash: string,
  syncedAt: number,
  revision: number,
): Promise<boolean> {
  assertGalleryRevision(revision);
  assertGalleryTimestamp("tag sync", syncedAt);
  assertGalleryPositions("tag", tags);
  return db.transaction(async (tx) => {
    const channelState = await lockGalleryChannelSyncState(tx, channelId);
    if (
      channelState.tags_revision >= revision ||
      (channelState.deleted_revision !== null &&
        channelState.deleted_revision >= revision)
    ) {
      return false;
    }
    const tagsChanged = channelState.tags_hash !== tagsHash;
    await tx
      .update(galleryChannelSyncState)
      .set({ tags_revision: revision, tags_hash: tagsHash })
      .where(eq(galleryChannelSyncState.channel_id, channelId));

    await tx
      .update(galleryTags)
      .set({ available: false, last_synced_at: syncedAt })
      .where(eq(galleryTags.channel_id, channelId));

    if (tags.length > 0) {
      await tx
        .insert(galleryTags)
        .values(
          tags.map((tag) => ({
            discord_tag_id: tag.discordTagId,
            channel_id: channelId,
            name: tag.name,
            emoji_id: tag.emojiId,
            emoji_name: tag.emojiName,
            moderated: tag.moderated,
            available: true,
            position: tag.position,
            last_synced_at: syncedAt,
          })),
        )
        .onConflictDoUpdate({
          target: galleryTags.discord_tag_id,
          set: {
            channel_id: sql`excluded.channel_id`,
            name: sql`excluded.name`,
            emoji_id: sql`excluded.emoji_id`,
            emoji_name: sql`excluded.emoji_name`,
            moderated: sql`excluded.moderated`,
            available: true,
            position: sql`excluded.position`,
            last_synced_at: sql`excluded.last_synced_at`,
          },
        });
    }
    if (tagsChanged) {
      await tx
        .update(galleryPosts)
        .set({
          content_updated_at: sql`GREATEST(${galleryPosts.content_updated_at}, ${syncedAt})`,
        })
        .where(
          and(
            eq(galleryPosts.channel_id, channelId),
            eq(galleryPosts.published, true),
            isNull(galleryPosts.deleted_at),
          ),
        );
    }
    return true;
  });
}

export async function markGalleryPostDeleted(
  threadId: string,
  deletedAt: number,
  revision: number,
): Promise<boolean> {
  assertGalleryRevision(revision);
  assertGalleryTimestamp("post deletion", deletedAt);
  return db.transaction(async (tx) => {
    const accepted = await acceptGalleryPostRevisions(
      tx,
      [threadId],
      revision,
    );
    if (accepted.length === 0) return false;
    await queueAndRemoveGalleryImages(tx, accepted, deletedAt);
    await tx
      .update(galleryPosts)
      .set({
        published: false,
        deleted_at: deletedAt,
        last_synced_at: deletedAt,
      })
      .where(eq(galleryPosts.thread_id, threadId));
    return true;
  });
}

export async function markGalleryChannelDeleted(
  channelId: string,
  deletedAt: number,
  revision: number,
): Promise<boolean> {
  assertGalleryRevision(revision);
  assertGalleryTimestamp("channel deletion", deletedAt);
  return db.transaction(async (tx) => {
    const channelState = await lockGalleryChannelSyncState(tx, channelId);
    if (
      channelState.deleted_revision !== null &&
      channelState.deleted_revision >= revision
    ) {
      return false;
    }
    await tx
      .update(galleryChannelSyncState)
      .set({ deleted_revision: revision })
      .where(eq(galleryChannelSyncState.channel_id, channelId));
    await tx
      .update(galleryTags)
      .set({ available: false, last_synced_at: deletedAt })
      .where(eq(galleryTags.channel_id, channelId));

    const posts = await tx
      .select({ threadId: galleryPosts.thread_id })
      .from(galleryPosts)
      .where(eq(galleryPosts.channel_id, channelId));
    const accepted = await acceptGalleryPostRevisions(
      tx,
      posts.map((post) => post.threadId),
      revision,
    );
    await queueAndRemoveGalleryImages(tx, accepted, deletedAt);
    if (accepted.length > 0) {
      await tx
        .update(galleryPosts)
        .set({
          published: false,
          deleted_at: deletedAt,
          last_synced_at: deletedAt,
        })
        .where(inArray(galleryPosts.thread_id, accepted));
    }
    return true;
  });
}

/**
 * Reconcile a complete channel backfill, retaining rows for audit while
 * removing posts no longer present in Discord from the public website.
 */
export async function markGalleryPostsDeletedExcept(
  channelId: string,
  liveThreadIds: string[],
  deletedAt: number,
  revision: number,
): Promise<number> {
  assertGalleryRevision(revision);
  assertGalleryTimestamp("reconciliation", deletedAt);
  return db.transaction(async (tx) => {
    const channelState = await lockGalleryChannelSyncState(tx, channelId);
    if (
      channelState.deleted_revision !== null &&
      channelState.deleted_revision >= revision
    ) {
      return 0;
    }
    const conditions = [eq(galleryPosts.channel_id, channelId)];
    if (liveThreadIds.length > 0) {
      conditions.push(
        notInArray(galleryPosts.thread_id, [...new Set(liveThreadIds)]),
      );
    }
    const posts = await tx
      .select({ threadId: galleryPosts.thread_id })
      .from(galleryPosts)
      .where(and(...conditions));
    const accepted = await acceptGalleryPostRevisions(
      tx,
      posts.map((post) => post.threadId),
      revision,
    );
    await queueAndRemoveGalleryImages(tx, accepted, deletedAt);
    if (accepted.length > 0) {
      await tx
        .update(galleryPosts)
        .set({
          published: false,
          deleted_at: deletedAt,
          last_synced_at: deletedAt,
        })
        .where(inArray(galleryPosts.thread_id, accepted));
    }
    return accepted.length;
  });
}

/**
 * Queue uploads produced by a stale sync only when no accepted Gallery image
 * currently references their deterministic key. A later accepted upsert
 * cancels the queue row if that key becomes current during the grace period.
 */
export async function enqueueUnreferencedGalleryStorageDeletions(
  images: GalleryStoredMediaInput[],
  queuedAt: number,
): Promise<number> {
  assertGalleryTimestamp("storage queue", queuedAt);
  const uniqueImages = Array.from(
    new Map(images.map((image) => [image.storageKey, image])).values(),
  );
  if (uniqueImages.length === 0) return 0;
  return db.transaction(async (tx) => {
    await lockGalleryStorageKeys(
      tx,
      uniqueImages.map((image) => image.storageKey),
    );
    const referenced = await tx
      .select({ storageKey: galleryImages.storage_key })
      .from(galleryImages)
      .where(
        inArray(
          galleryImages.storage_key,
          uniqueImages.map((image) => image.storageKey),
        ),
      );
    const referencedKeys = new Set(referenced.map((row) => row.storageKey));
    const unreferenced = uniqueImages.filter(
      (image) => !referencedKeys.has(image.storageKey),
    );
    if (unreferenced.length > 0) {
      await tx
        .insert(galleryStorageDeletions)
        .values(
          unreferenced.map((image) => ({
            storage_key: image.storageKey,
            public_url: image.publicUrl,
            queued_at: queuedAt,
            delete_after:
              queuedAt + GALLERY_STORAGE_DELETE_GRACE_SECONDS,
          })),
        )
        .onConflictDoUpdate({
          target: galleryStorageDeletions.storage_key,
          // The stale sync has just uploaded this deterministic key again.
          // Reset older unclaimed retry state so it receives a full grace.
          set: {
            public_url: sql`excluded.public_url`,
            queued_at: sql`excluded.queued_at`,
            delete_after: sql`excluded.delete_after`,
            attempts: 0,
            last_attempt_at: null,
            last_error: null,
          },
          // Never cancel a delete that has crossed the database/storage
          // boundary. The owning sync will retry its upload after that claim.
          setWhere: isNull(galleryStorageDeletions.last_attempt_at),
        });
    }
    return unreferenced.length;
  });
}

/** Atomically lease due media deletions; expired leases become retryable. */
export async function claimDueGalleryStorageDeletions(
  now: number,
  limit: number,
  leaseUntil: number,
): Promise<GalleryStorageDeletionClaim[]> {
  assertGalleryTimestamp("storage claim", now);
  assertGalleryTimestamp("storage lease", leaseUntil);
  if (!Number.isSafeInteger(limit) || limit <= 0) {
    throw new Error("Gallery storage deletion claim limit must be positive");
  }
  const safeLimit = Math.min(1_000, limit);
  if (leaseUntil <= now) {
    throw new Error("Gallery storage deletion lease must end after it starts");
  }
  const rows = await db.execute(sql`
    WITH due AS (
      SELECT storage_key
      FROM gallery_storage_deletions
      WHERE delete_after <= ${now}
      ORDER BY delete_after ASC, storage_key ASC
      FOR UPDATE SKIP LOCKED
      LIMIT ${safeLimit}
    )
    UPDATE gallery_storage_deletions AS deletion
    SET attempts = deletion.attempts + 1,
        last_attempt_at = ${now},
        delete_after = ${leaseUntil},
        last_error = NULL
    FROM due
    WHERE deletion.storage_key = due.storage_key
    RETURNING deletion.storage_key,
              deletion.public_url,
              deletion.attempts,
              deletion.last_attempt_at,
              deletion.delete_after
  `);
  return (
    rows as unknown as Array<{
      storage_key: string;
      public_url: string;
      attempts: number;
      last_attempt_at: number;
      delete_after: number;
    }>
  ).map((row) => ({
    storageKey: row.storage_key,
    publicUrl: row.public_url,
    attempts: Number(row.attempts),
    claimedAt: Number(row.last_attempt_at),
    leaseUntil: Number(row.delete_after),
  }));
}

/**
 * Execute one leased deletion while fencing every database path that can
 * reserve or reference its deterministic object key. Keeping the advisory
 * lock through the external callback is deliberate: a writer either cancels
 * the queue before checking storage, or waits until this deletion has finished
 * and then checks/uploads the object afterwards.
 */
export async function executeGalleryStorageDeletionClaim(
  claim: GalleryStorageDeletionClaim,
  deleteObject: () => Promise<void>,
): Promise<GalleryStorageDeletionExecutionResult> {
  return db.transaction(async (tx) => {
    await lockGalleryStorageKeys(tx, [claim.storageKey]);

    const claimIdentity = and(
      eq(galleryStorageDeletions.storage_key, claim.storageKey),
      eq(galleryStorageDeletions.attempts, claim.attempts),
      eq(galleryStorageDeletions.last_attempt_at, claim.claimedAt),
      eq(galleryStorageDeletions.delete_after, claim.leaseUntil),
    );
    const [currentClaim] = await tx
      .select({ storageKey: galleryStorageDeletions.storage_key })
      .from(galleryStorageDeletions)
      .where(
        and(
          claimIdentity,
          sql`${galleryStorageDeletions.delete_after} > FLOOR(EXTRACT(EPOCH FROM clock_timestamp()))::integer`,
        ),
      )
      .for("update");
    if (!currentClaim) return "stale-claim";

    const [reference] = await tx
      .select({ storageKey: galleryImages.storage_key })
      .from(galleryImages)
      .where(eq(galleryImages.storage_key, claim.storageKey))
      .limit(1);
    if (reference) {
      await tx.delete(galleryStorageDeletions).where(claimIdentity);
      return "referenced";
    }

    // This callback must only perform the storage/cache deletion. It must not
    // call back into a Gallery database query while the per-key lock is held.
    await deleteObject();
    const completed = await tx
      .delete(galleryStorageDeletions)
      .where(claimIdentity)
      .returning({ storageKey: galleryStorageDeletions.storage_key });
    if (completed.length !== 1) {
      throw new Error(
        `Gallery storage claim changed during deletion: ${claim.storageKey}`,
      );
    }
    return "deleted";
  });
}

export async function recordGalleryStorageDeletionFailure(
  claim: GalleryStorageDeletionClaim,
  failedAt: number,
  error: string,
): Promise<boolean> {
  assertGalleryTimestamp("storage failure", failedAt);
  const retrySeconds = Math.min(
    GALLERY_STORAGE_RETRY_MAX_SECONDS,
    30 * 2 ** Math.min(10, Math.max(0, claim.attempts - 1)),
  );
  const rows = await db
    .update(galleryStorageDeletions)
    .set({
      delete_after: failedAt + retrySeconds,
      // The external attempt has returned, so a writer may now cancel this
      // retry and re-upload the deterministic key without racing a delete.
      last_attempt_at: null,
      last_error: error.slice(0, 4_000),
    })
    .where(
      and(
        eq(galleryStorageDeletions.storage_key, claim.storageKey),
        eq(galleryStorageDeletions.attempts, claim.attempts),
        eq(galleryStorageDeletions.last_attempt_at, claim.claimedAt),
        eq(galleryStorageDeletions.delete_after, claim.leaseUntil),
      ),
    )
    .returning({ storageKey: galleryStorageDeletions.storage_key });
  return rows.length > 0;
}

export async function upsertUser(data: UpsertUserData): Promise<void> {
  await db.transaction(async (tx) => {
    // players.minecraft_uuid is unique; if some other discord_id currently
    // owns this UUID (e.g. a prior denied/cancelled application), detach it
    // so the upsert's ON CONFLICT (discord_id) clause can resolve cleanly.
    if (data.minecraftUuid) {
      await tx
        .update(players)
        .set({ minecraft_uuid: null, minecraft_username: null })
        .where(
          and(
            eq(players.minecraft_uuid, data.minecraftUuid),
            ne(players.discord_id, data.discordId),
          ),
        );
    }

    await tx
      .insert(players)
      .values({
        discord_id: data.discordId,
        discord_username: data.discordUsername,
        minecraft_username: data.minecraftUsername ?? null,
        minecraft_uuid: data.minecraftUuid ?? null,
        is_discord_member: true,
      })
      .onConflictDoUpdate({
        target: players.discord_id,
        set: {
          discord_username: sql`excluded.discord_username`,
          minecraft_username: sql`COALESCE(excluded.minecraft_username, ${players.minecraft_username})`,
          minecraft_uuid: sql`COALESCE(excluded.minecraft_uuid, ${players.minecraft_uuid})`,
          is_discord_member: true,
          updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        },
      });
  });
}

export async function createApplication(
  data: CreateApplicationData,
): Promise<{ id: number; appliedAt: number }> {
  // Applications are unique per (discord_id, season). A re-application for
  // the same season (e.g. after a denial) upserts onto the existing row and
  // resets it to a fresh pending state. When season is null the unique index
  // treats the rows as distinct, so this simply inserts a new row.
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .insert(applications)
    .values({
      discord_id: data.discordId,
      discord_username: data.discordUsername,
      minecraft_username: data.minecraftUsername,
      minecraft_uuid: data.minecraftUuid ?? null,
      age_met: data.ageMet,
      voice_chat: data.voiceChat,
      join_reason: data.joinReason ?? null,
      favourite_wood: data.favouriteWood ?? null,
      season: data.season ?? null,
      applied_at: now,
    })
    .onConflictDoUpdate({
      target: [applications.discord_id, applications.season],
      set: {
        discord_username: sql`excluded.discord_username`,
        minecraft_username: sql`excluded.minecraft_username`,
        minecraft_uuid: sql`excluded.minecraft_uuid`,
        age_met: sql`excluded.age_met`,
        voice_chat: sql`excluded.voice_chat`,
        join_reason: sql`excluded.join_reason`,
        favourite_wood: sql`excluded.favourite_wood`,
        status: "pending",
        policy_agreed: false,
        denial_reason: null,
        resolved_at: null,
        resolved_by_discord_id: null,
        applied_at: now,
      },
    })
    .returning({ id: applications.id, appliedAt: applications.applied_at });
  return row;
}

export async function setPolicyAgreed(
  discordId: string,
  agreed: boolean,
): Promise<void> {
  // Find the latest pending application for this user, then update it
  const [latest] = await db
    .select({ id: applications.id })
    .from(applications)
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .orderBy(desc(applications.applied_at))
    .limit(1);

  if (latest) {
    await db
      .update(applications)
      .set({ policy_agreed: agreed })
      .where(eq(applications.id, latest.id));
  }
}

export async function hasPendingApplication(
  discordId: string,
): Promise<boolean> {
  const rows = await db
    .select({ id: applications.id })
    .from(applications)
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .limit(1);
  return rows.length > 0;
}

/**
 * Atomically transition the applicant's pending application to accepted.
 * Returns true only if a pending row was actually updated — callers use this
 * as a lock to guard against two moderators accepting at the same time.
 */
export async function acceptApplication(
  discordId: string,
  resolvedBy: string,
): Promise<boolean> {
  const now = Math.floor(Date.now() / 1000);
  const updated = await db
    .update(applications)
    .set({
      status: "accepted",
      resolved_at: now,
      resolved_by_discord_id: resolvedBy,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .returning({ id: applications.id });
  return updated.length > 0;
}

/**
 * Roll an accepted application back to pending. Used to undo the accept-time
 * status flip (the double-accept guard) when a later step — e.g. the whitelist
 * insert — fails, so a moderator can simply retry.
 */
export async function revertApplicationToPending(
  discordId: string,
): Promise<void> {
  await db
    .update(applications)
    .set({
      status: "pending",
      resolved_at: null,
      resolved_by_discord_id: null,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "accepted"),
      ),
    );
}

export async function cancelPendingApplications(
  discordId: string,
): Promise<void> {
  await db
    .update(applications)
    .set({
      status: "cancelled",
      resolved_at: Math.floor(Date.now() / 1000),
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    );
}

export async function denyApplication(
  discordId: string,
  reason: string,
  resolvedBy: string,
): Promise<boolean> {
  const updated = await db
    .update(applications)
    .set({
      status: "denied",
      denial_reason: reason,
      resolved_at: Math.floor(Date.now() / 1000),
      resolved_by_discord_id: resolvedBy,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .returning({ id: applications.id });
  return updated.length > 0;
}

export async function getLatestApplication(discordId: string) {
  const rows = await db
    .select()
    .from(applications)
    .where(eq(applications.discord_id, discordId))
    .orderBy(desc(applications.applied_at))
    .limit(1);
  return rows[0] ?? null;
}

export async function updateApplication(
  discordId: string,
  data: {
    minecraftUsername: string;
    minecraftUuid: string;
    ageMet: boolean;
    voiceChat: boolean;
    joinReason?: string;
    favouriteWood?: string;
  },
): Promise<void> {
  await db
    .update(applications)
    .set({
      minecraft_username: data.minecraftUsername,
      minecraft_uuid: data.minecraftUuid,
      age_met: data.ageMet,
      voice_chat: data.voiceChat,
      join_reason: data.joinReason ?? null,
      favourite_wood: data.favouriteWood ?? null,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    );
}

// ── Stream channels ────────────────────────────────────────────

export type Platform = "youtube" | "twitch" | "tiktok";

export interface StreamChannel {
  id: number;
  platform: Platform;
  channel_id: string;
  discord_user_id: string;
  display_name: string | null;
}

export interface StreamChannelPollCandidate extends StreamChannel {
  player_role: "unverified" | "verified" | "moderator" | "admin";
}

export async function addStreamChannel(
  platform: Platform,
  channelId: string,
  discordUserId: string,
  displayName?: string,
): Promise<void> {
  await db
    .insert(streamChannels)
    .values({
      platform,
      channel_id: channelId,
      discord_user_id: discordUserId,
      display_name: displayName ?? null,
    })
    .onConflictDoUpdate({
      target: [streamChannels.platform, streamChannels.channel_id],
      set: {
        discord_user_id: sql`excluded.discord_user_id`,
        display_name: sql`COALESCE(excluded.display_name, ${streamChannels.display_name})`,
      },
    });
}

export async function removeStreamChannel(
  platform: Platform,
  channelId: string,
): Promise<boolean> {
  const result = await db
    .delete(streamChannels)
    .where(
      and(
        eq(streamChannels.platform, platform),
        eq(streamChannels.channel_id, channelId),
      ),
    );
  return (result as any).rowCount > 0;
}

export async function getAllStreamChannels(): Promise<StreamChannel[]> {
  const rows = await db.select().from(streamChannels);
  return rows.map((r) => ({
    id: r.id,
    platform: r.platform as Platform,
    channel_id: r.channel_id,
    discord_user_id: r.discord_user_id,
    display_name: r.display_name,
  }));
}

export async function getStreamChannelsByPlatform(
  platform: Platform,
): Promise<StreamChannel[]> {
  const rows = await db
    .select()
    .from(streamChannels)
    .where(eq(streamChannels.platform, platform));
  return rows.map((r) => ({
    id: r.id,
    platform: r.platform as Platform,
    channel_id: r.channel_id,
    discord_user_id: r.discord_user_id,
    display_name: r.display_name,
  }));
}

export async function getVerifiedStreamChannelsByPlatform(
  platform: Platform,
): Promise<StreamChannelPollCandidate[]> {
  const rows = await db
    .select({
      id: streamChannels.id,
      platform: streamChannels.platform,
      channel_id: streamChannels.channel_id,
      discord_user_id: streamChannels.discord_user_id,
      display_name: streamChannels.display_name,
      player_role: players.role,
    })
    .from(streamChannels)
    .innerJoin(players, eq(streamChannels.discord_user_id, players.discord_id))
    .where(
      and(
        eq(streamChannels.platform, platform),
        ne(players.role, "unverified"),
      ),
    )
    .orderBy(asc(streamChannels.id));

  return rows.map((row) => ({
    ...row,
    platform: row.platform as Platform,
  }));
}

// ── Player alts ────────────────────────────────────────────────

export const MAX_ALTS = 2;

export interface PlayerAlt {
  id: number;
  discord_id: string;
  minecraft_uuid: string;
  minecraft_username: string;
  created_at: number;
}

export async function addPlayerAlt(
  discordId: string,
  minecraftUuid: string,
  minecraftUsername: string,
): Promise<void> {
  await db.insert(playerAlts).values({
    discord_id: discordId,
    minecraft_uuid: minecraftUuid,
    minecraft_username: minecraftUsername,
  });
}

export async function removePlayerAlt(
  discordId: string,
  minecraftUuid: string,
): Promise<boolean> {
  const result = await db
    .delete(playerAlts)
    .where(
      and(
        eq(playerAlts.discord_id, discordId),
        eq(playerAlts.minecraft_uuid, minecraftUuid),
      ),
    );
  return (result as any).rowCount > 0;
}

export async function getPlayerAlts(discordId: string): Promise<PlayerAlt[]> {
  const rows = await db
    .select()
    .from(playerAlts)
    .where(eq(playerAlts.discord_id, discordId));
  return rows as PlayerAlt[];
}

export async function getAltCountForUser(discordId: string): Promise<number> {
  const rows = await db
    .select({ count: sql<number>`COUNT(*)::INTEGER` })
    .from(playerAlts)
    .where(eq(playerAlts.discord_id, discordId));
  return rows[0]?.count ?? 0;
}

export async function deleteAllAltsForUser(discordId: string): Promise<void> {
  await db.delete(playerAlts).where(eq(playerAlts.discord_id, discordId));
}

export async function getPlayerPrimaryUuid(discordId: string): Promise<string | null> {
  const rows = await db
    .select({ minecraft_uuid: players.minecraft_uuid })
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  return rows[0]?.minecraft_uuid ?? null;
}

export async function isAltUuidTaken(minecraftUuid: string): Promise<boolean> {
  const rows = await db
    .select({ id: playerAlts.id })
    .from(playerAlts)
    .where(eq(playerAlts.minecraft_uuid, minecraftUuid))
    .limit(1);
  return rows.length > 0;
}

// ── Starboard ──────────────────────────────────────────────────

export interface StarboardPost {
  message_id: string;
  channel_id: string;
  author_id: string;
  starboard_message_id: string | null;
  trigger_emoji_id: string | null;
  trigger_emoji_name: string | null;
  trigger_emoji_animated: boolean;
  posted_at: number;
}

export async function hasStarboardPost(messageId: string): Promise<boolean> {
  const rows = await db
    .select({ message_id: starboardPosts.message_id })
    .from(starboardPosts)
    .where(eq(starboardPosts.message_id, messageId))
    .limit(1);
  return rows.length > 0;
}

export async function getStarboardPost(
  messageId: string,
): Promise<StarboardPost | null> {
  const [row] = await db
    .select()
    .from(starboardPosts)
    .where(eq(starboardPosts.message_id, messageId))
    .limit(1);
  return (row as StarboardPost | undefined) ?? null;
}

/**
 * Atomically claims a message for starboard reposting. Returns `true`
 * if this caller won the race and should send the starboard message,
 * `false` if another caller already claimed it.
 */
export async function claimStarboardPost(data: {
  messageId: string;
  channelId: string;
  authorId: string;
  triggerEmojiId: string | null;
  triggerEmojiName: string | null;
  triggerEmojiAnimated: boolean;
}): Promise<boolean> {
  const inserted = await db
    .insert(starboardPosts)
    .values({
      message_id: data.messageId,
      channel_id: data.channelId,
      author_id: data.authorId,
      trigger_emoji_id: data.triggerEmojiId,
      trigger_emoji_name: data.triggerEmojiName,
      trigger_emoji_animated: data.triggerEmojiAnimated,
    })
    .onConflictDoNothing()
    .returning({ message_id: starboardPosts.message_id });
  return inserted.length > 0;
}

export async function setStarboardMessageId(
  messageId: string,
  starboardMessageId: string,
): Promise<void> {
  await db
    .update(starboardPosts)
    .set({ starboard_message_id: starboardMessageId })
    .where(eq(starboardPosts.message_id, messageId));
}

export async function deleteStarboardPost(messageId: string): Promise<void> {
  await db
    .delete(starboardPosts)
    .where(eq(starboardPosts.message_id, messageId));
}

export async function getStarboardPostsByAuthor(
  authorId: string,
): Promise<StarboardPost[]> {
  const rows = await db
    .select()
    .from(starboardPosts)
    .where(eq(starboardPosts.author_id, authorId))
    .orderBy(desc(starboardPosts.posted_at));
  return rows as StarboardPost[];
}

// ── Counting ───────────────────────────────────────────────────

export interface CountingState {
  channel_id: string;
  current_count: number;
  last_user_id: string | null;
  updated_at: number;
}

export async function getCountingState(
  channelId: string,
): Promise<CountingState | null> {
  const [row] = await db
    .select()
    .from(countingState)
    .where(eq(countingState.channel_id, channelId))
    .limit(1);
  return (row as CountingState | undefined) ?? null;
}

/**
 * Atomically advances the count by 1 only when the row's
 * `current_count` still equals `expectedCurrent` AND the last
 * counter wasn't this user. Returns true if the row was updated.
 */
export async function tryAdvanceCount(
  channelId: string,
  expectedCurrent: number,
  userId: string,
): Promise<boolean> {
  const now = Math.floor(Date.now() / 1000);
  const updated = await db
    .update(countingState)
    .set({
      current_count: expectedCurrent + 1,
      last_user_id: userId,
      updated_at: now,
    })
    .where(
      and(
        eq(countingState.channel_id, channelId),
        eq(countingState.current_count, expectedCurrent),
        sql`(${countingState.last_user_id} IS NULL OR ${countingState.last_user_id} <> ${userId})`,
      ),
    )
    .returning({ channel_id: countingState.channel_id });
  return updated.length > 0;
}

/** Mod-only seed/override. Upserts the counting state row. */
export async function setCountingState(
  channelId: string,
  count: number,
  lastUserId: string | null = null,
): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  await db
    .insert(countingState)
    .values({
      channel_id: channelId,
      current_count: count,
      last_user_id: lastUserId,
      updated_at: now,
    })
    .onConflictDoUpdate({
      target: countingState.channel_id,
      set: {
        current_count: count,
        last_user_id: lastUserId,
        updated_at: now,
      },
    });
}

// ── Tickets ────────────────────────────────────────────────────

/** Max simultaneous open tickets per category per user. */
export const MAX_OPEN_TICKETS_PER_CATEGORY = 3;

export interface CreateTicketData {
  channelId: string;
  parentCategoryId: string;
  guildId: string;
  openerDiscordId: string;
  openerDiscordUsername: string;
  openerMinecraftUuid?: string | null;
  openerMinecraftUsername?: string | null;
  category: TicketCategory;
  subject?: string | null;
  intake: Record<string, unknown>;
}

export type Ticket = typeof tickets.$inferSelect;

export async function createTicket(data: CreateTicketData): Promise<Ticket> {
  const [row] = await db
    .insert(tickets)
    .values({
      channel_id: data.channelId,
      parent_category_id: data.parentCategoryId,
      guild_id: data.guildId,
      opener_discord_id: data.openerDiscordId,
      opener_discord_username: data.openerDiscordUsername,
      opener_minecraft_uuid: data.openerMinecraftUuid ?? null,
      opener_minecraft_username: data.openerMinecraftUsername ?? null,
      category: data.category,
      subject: data.subject ?? null,
      intake: data.intake,
    })
    .returning();
  return row;
}

export async function getTicketByChannelId(
  channelId: string,
): Promise<Ticket | null> {
  const [row] = await db
    .select()
    .from(tickets)
    .where(eq(tickets.channel_id, channelId))
    .limit(1);
  return row ?? null;
}

export async function getTicketById(id: number): Promise<Ticket | null> {
  const [row] = await db
    .select()
    .from(tickets)
    .where(eq(tickets.id, id))
    .limit(1);
  return row ?? null;
}

export async function countOpenTicketsForUserAndCategory(
  discordId: string,
  category: TicketCategory,
): Promise<number> {
  const rows = await db
    .select({ count: sql<number>`COUNT(*)::INTEGER` })
    .from(tickets)
    .where(
      and(
        eq(tickets.opener_discord_id, discordId),
        eq(tickets.category, category),
        eq(tickets.status, "open"),
      ),
    );
  return rows[0]?.count ?? 0;
}

export async function closeTicket(
  ticketId: number,
  closedByDiscordId: string,
  deleteAfter: number,
): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .update(tickets)
    .set({
      status: "closed",
      closed_by_discord_id: closedByDiscordId,
      closed_at: now,
      delete_after: deleteAfter,
      updated_at: now,
    })
    .where(
      and(
        eq(tickets.id, ticketId),
        eq(tickets.status, "open"),
      ),
    )
    .returning();
  return row ?? null;
}

export async function reopenTicket(ticketId: number): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .update(tickets)
    .set({
      status: "open",
      closed_by_discord_id: null,
      closed_at: null,
      delete_after: null,
      updated_at: now,
    })
    .where(
      and(
        eq(tickets.id, ticketId),
        eq(tickets.status, "closed"),
      ),
    )
    .returning();
  return row ?? null;
}

export async function getExpiredClosedTickets(now: number): Promise<Ticket[]> {
  return db
    .select()
    .from(tickets)
    .where(
      and(
        eq(tickets.status, "closed"),
        lte(tickets.delete_after, now),
      ),
    );
}

export async function deleteTicketRow(ticketId: number): Promise<void> {
  await db.delete(tickets).where(eq(tickets.id, ticketId));
}

export async function listOpenTicketsForUser(
  discordId: string,
): Promise<Ticket[]> {
  return db
    .select()
    .from(tickets)
    .where(
      and(
        eq(tickets.opener_discord_id, discordId),
        eq(tickets.status, "open"),
      ),
    )
    .orderBy(desc(tickets.created_at));
}

/** Minimal player lookup used to populate the ticket header card. */
export async function getPlayerLink(
  discordId: string,
): Promise<{
  minecraft_username: string | null;
  minecraft_uuid: string | null;
} | null> {
  const [row] = await db
    .select({
      minecraft_username: players.minecraft_username,
      minecraft_uuid: players.minecraft_uuid,
    })
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  return row ?? null;
}

// ── Identity sync ───────────────────────────────────────────────

export interface SyncablePlayerIdentity {
  discord_id: string;
  discord_username: string;
  minecraft_uuid: string | null;
  minecraft_username: string | null;
}

export interface SyncablePlayerAltIdentity {
  minecraft_uuid: string;
  minecraft_username: string;
}

const RESET_AWARD_MEDALS = sql`
  UPDATE player_award_scores
  SET medal = 0
`;

const RECOMPUTE_ELIGIBLE_AWARD_MEDALS = sql`
  WITH ranked AS (
    SELECT
      scores.id,
      RANK() OVER (
        PARTITION BY scores.season, scores.award_id
        ORDER BY scores.score DESC
      ) AS rank
    FROM player_award_scores scores
    WHERE scores.score > 0
      AND NOT EXISTS (
        SELECT 1 FROM player_alts alt
        WHERE alt.minecraft_uuid = scores.minecraft_uuid
      )
      AND EXISTS (
        SELECT 1 FROM players eligible_player
        WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
          AND eligible_player.is_discord_member = true
      )
  )
  UPDATE player_award_scores scores
  SET medal = ranked.rank::int
  FROM ranked
  WHERE scores.id = ranked.id AND ranked.rank <= 3
`;

export async function getSyncablePlayerIdentities(): Promise<SyncablePlayerIdentity[]> {
  return db
    .select({
      discord_id: players.discord_id,
      discord_username: players.discord_username,
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
    })
    .from(players);
}

/** Update one player's durable Discord-guild membership state. */
export async function setPlayerDiscordMembership(
  discordId: string,
  isMember: boolean,
): Promise<void> {
  await db.transaction(async (tx) => {
    const changedRows = await tx
      .update(players)
      .set({
        is_discord_member: isMember,
        updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
      })
      .where(
        and(
          eq(players.discord_id, discordId),
          ne(players.is_discord_member, isMember),
        ),
      )
      .returning({ discord_id: players.discord_id });

    if (changedRows.length === 0) return;

    await tx.execute(RESET_AWARD_MEDALS);
    await tx.execute(RECOMPUTE_ELIGIBLE_AWARD_MEDALS);
  });
}

/**
 * Replace the stored membership snapshot atomically.
 *
 * Existing rows default to membership=true when the column is first deployed;
 * this startup reconciliation is therefore also the backfill for players who
 * left before membership tracking existed.
 */
export async function reconcilePlayerDiscordMembership(
  guildMemberDiscordIds: readonly string[],
): Promise<{ members: number; nonMembers: number }> {
  return db.transaction(async (tx) => {
    await tx
      .update(players)
      .set({
        is_discord_member: false,
        updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
      });

    if (guildMemberDiscordIds.length > 0) {
      await tx
        .update(players)
        .set({
          is_discord_member: true,
          updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        })
        .where(inArray(players.discord_id, [...guildMemberDiscordIds]));
    }

    // Membership changes alter podium positions. Reassign every season's
    // persisted medals inside the same transaction as the membership seed.
    await tx.execute(RESET_AWARD_MEDALS);
    await tx.execute(RECOMPUTE_ELIGIBLE_AWARD_MEDALS);

    const [counts] = await tx
      .select({
        members: sql<number>`COUNT(*) FILTER (WHERE ${players.is_discord_member} = true)::int`,
        nonMembers: sql<number>`COUNT(*) FILTER (WHERE ${players.is_discord_member} = false)::int`,
      })
      .from(players);

    return {
      members: Number(counts?.members ?? 0),
      nonMembers: Number(counts?.nonMembers ?? 0),
    };
  });
}

export async function getSyncablePlayerAltIdentities(): Promise<SyncablePlayerAltIdentity[]> {
  return db
    .select({
      minecraft_uuid: playerAlts.minecraft_uuid,
      minecraft_username: playerAlts.minecraft_username,
    })
    .from(playerAlts);
}

export interface PunishmentRoleSyncAccount {
  discord_id: string;
  minecraft_uuid: string;
}

export async function getPunishmentRoleSyncAccounts(): Promise<PunishmentRoleSyncAccount[]> {
  const primaryRows = await db
    .select({
      discord_id: players.discord_id,
      minecraft_uuid: players.minecraft_uuid,
    })
    .from(players)
    .where(isNotNull(players.minecraft_uuid));

  const altRows = await db
    .select({
      discord_id: playerAlts.discord_id,
      minecraft_uuid: playerAlts.minecraft_uuid,
    })
    .from(playerAlts);

  return [
    ...primaryRows.filter(
      (row): row is PunishmentRoleSyncAccount => row.minecraft_uuid !== null,
    ),
    ...altRows,
  ];
}

export async function updatePlayerDiscordUsername(
  discordId: string,
  discordUsername: string,
): Promise<void> {
  await db
    .update(players)
    .set({
      discord_username: discordUsername,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.discord_id, discordId));
}

export async function updatePlayerMinecraftUsername(
  minecraftUuid: string,
  minecraftUsername: string,
): Promise<void> {
  await db
    .update(players)
    .set({
      minecraft_username: minecraftUsername,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.minecraft_uuid, minecraftUuid));
}

export async function updateAltMinecraftUsername(
  minecraftUuid: string,
  minecraftUsername: string,
): Promise<void> {
  await db
    .update(playerAlts)
    .set({ minecraft_username: minecraftUsername })
    .where(eq(playerAlts.minecraft_uuid, minecraftUuid));
}

/**
 * Unlink a Minecraft account from whichever player row currently holds it,
 * so the account can be re-linked (e.g. as another user's alt). Returns the
 * discord_id of the row that was unlinked, or null if no row held the uuid.
 */
export async function clearPlayerMinecraftLinkByUuid(
  uuid: string,
): Promise<string | null> {
  const [row] = await db
    .update(players)
    .set({
      minecraft_uuid: null,
      minecraft_username: null,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.minecraft_uuid, uuid))
    .returning({ discord_id: players.discord_id });
  return row?.discord_id ?? null;
}

/** Unlink a Discord user's Minecraft account (keeps the player row). */
export async function clearPlayerMinecraftLinkByDiscordId(
  discordId: string,
): Promise<void> {
  await db
    .update(players)
    .set({
      minecraft_uuid: null,
      minecraft_username: null,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.discord_id, discordId));
}

// ── player profile card (/playerinfo) ───────────────────────────

export interface PlayerIdentity {
  discord_id: string;
  minecraft_uuid: string;
  minecraft_username: string | null;
  nickname: string | null;
  discord_username: string;
  role: string;
}

const PLAYER_IDENTITY_COLUMNS = {
  discord_id: players.discord_id,
  minecraft_uuid: players.minecraft_uuid,
  minecraft_username: players.minecraft_username,
  nickname: players.nickname,
  discord_username: players.discord_username,
  role: players.role,
} as const;

/** Resolve a linked player by Minecraft username (case-insensitive). */
export async function getPlayerByMinecraftUsername(
  username: string,
): Promise<PlayerIdentity | null> {
  const [row] = await db
    .select(PLAYER_IDENTITY_COLUMNS)
    .from(players)
    .where(ilike(players.minecraft_username, username))
    .limit(1);
  if (!row?.minecraft_uuid) return null;
  return row as PlayerIdentity;
}

/** Resolve a linked player by Discord ID. */
export async function getPlayerByDiscordId(
  discordId: string,
): Promise<PlayerIdentity | null> {
  const [row] = await db
    .select(PLAYER_IDENTITY_COLUMNS)
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  if (!row?.minecraft_uuid) return null;
  return row as PlayerIdentity;
}

/** All linked identities for a one-off analytics person-profile sync. */
export async function getAllPlayerAnalyticsIdentities(): Promise<PlayerIdentity[]> {
  const rows = await db
    .select(PLAYER_IDENTITY_COLUMNS)
    .from(players)
    .where(isNotNull(players.minecraft_uuid));
  return rows.flatMap((row) =>
    row.minecraft_uuid
      ? [{ ...row, minecraft_uuid: row.minecraft_uuid }]
      : [],
  );
}

/** The linked Discord id for a Minecraft UUID, if any. */
export async function getDiscordIdByMinecraftUuid(
  uuid: string,
): Promise<string | null> {
  const [row] = await db
    .select({ discord_id: players.discord_id })
    .from(players)
    .where(eq(players.minecraft_uuid, uuid))
    .limit(1);
  return row?.discord_id ?? null;
}

/** Resolve a linked Minecraft UUID without exposing Discord identity to analytics. */
export async function getMinecraftUuidByDiscordId(
  discordId: string,
): Promise<string | null> {
  const [row] = await db
    .select({ minecraftUuid: players.minecraft_uuid })
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  return row?.minecraftUuid ?? null;
}

/** The linked Discord id for a Minecraft username (case-insensitive), if any. */
export async function getDiscordIdByMinecraftUsername(
  username: string,
): Promise<string | null> {
  const [row] = await db
    .select({ discord_id: players.discord_id })
    .from(players)
    .where(ilike(players.minecraft_username, username))
    .limit(1);
  return row?.discord_id ?? null;
}

/** Resolve a linked player by Minecraft UUID. */
export async function getPlayerByMinecraftUuid(
  uuid: string,
): Promise<PlayerIdentity | null> {
  const [row] = await db
    .select(PLAYER_IDENTITY_COLUMNS)
    .from(players)
    .where(eq(players.minecraft_uuid, uuid))
    .limit(1);
  if (!row?.minecraft_uuid) return null;
  return row as PlayerIdentity;
}

/** The currently active season, or null if none is flagged current. */
export async function getCurrentSeason(): Promise<{ id: string; name: string } | null> {
  const [row] = await db
    .select({ id: seasons.id, name: seasons.name })
    .from(seasons)
    .where(eq(seasons.is_current, true))
    .limit(1);
  return row ?? null;
}

/**
 * Search linked players by Minecraft username substring (case-insensitive) for
 * slash-command autocomplete. An empty query returns the first `limit` players
 * alphabetically. LIKE metacharacters in the query are escaped.
 */
export async function searchPlayersByUsername(
  query: string,
  limit = 25,
): Promise<{ minecraft_uuid: string; minecraft_username: string }[]> {
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
    })
    .from(players)
    .where(ilike(players.minecraft_username, `%${query.replace(/[%_\\]/g, (c) => "\\" + c)}%`))
    .orderBy(asc(players.minecraft_username))
    .limit(limit);
  return rows.filter(
    (r): r is { minecraft_uuid: string; minecraft_username: string } =>
      r.minecraft_uuid !== null && r.minecraft_username !== null,
  );
}

// ── Application channels ────────────────────────────────────────

export type ApplicationChannel = typeof applicationChannels.$inferSelect;

export interface CreateApplicationChannelData {
  channelId: string;
  applicantId: string;
  applicantUsername: string;
  guildId: string;
}

/**
 * Record an application channel. Upserts on the channel id so a reused
 * channel cleanly refreshes (and resets the reminder/deletion state).
 */
export async function createApplicationChannel(
  data: CreateApplicationChannelData,
): Promise<ApplicationChannel> {
  const [row] = await db
    .insert(applicationChannels)
    .values({
      channel_id: data.channelId,
      applicant_id: data.applicantId,
      applicant_username: data.applicantUsername,
      guild_id: data.guildId,
    })
    .onConflictDoUpdate({
      target: applicationChannels.channel_id,
      set: {
        applicant_id: sql`excluded.applicant_id`,
        applicant_username: sql`excluded.applicant_username`,
        reminded: false,
        delete_after: null,
        updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
      },
    })
    .returning();
  return row;
}

export async function getApplicationChannelByChannelId(
  channelId: string,
): Promise<ApplicationChannel | null> {
  const [row] = await db
    .select()
    .from(applicationChannels)
    .where(eq(applicationChannels.channel_id, channelId))
    .limit(1);
  return row ?? null;
}

/** The most recent application channel opened for a given applicant. */
export async function getApplicationChannelByApplicant(
  applicantId: string,
): Promise<ApplicationChannel | null> {
  const [row] = await db
    .select()
    .from(applicationChannels)
    .where(eq(applicationChannels.applicant_id, applicantId))
    .orderBy(desc(applicationChannels.created_at))
    .limit(1);
  return row ?? null;
}

export async function markApplicationChannelReminded(
  channelId: string,
): Promise<void> {
  await db
    .update(applicationChannels)
    .set({ reminded: true, updated_at: Math.floor(Date.now() / 1000) })
    .where(eq(applicationChannels.channel_id, channelId));
}

export async function setApplicationChannelDeleteAfter(
  channelId: string,
  deleteAfter: number,
): Promise<void> {
  await db
    .update(applicationChannels)
    .set({
      delete_after: deleteAfter,
      updated_at: Math.floor(Date.now() / 1000),
    })
    .where(eq(applicationChannels.channel_id, channelId));
}

/**
 * Application channels due a "you haven't applied yet" reminder: created
 * before `createdBefore` (unix seconds), not yet reminded, and not already
 * scheduled for deletion (i.e. still awaiting an application).
 */
export async function getApplicationChannelsNeedingReminder(
  createdBefore: number,
): Promise<ApplicationChannel[]> {
  return db
    .select()
    .from(applicationChannels)
    .where(
      and(
        eq(applicationChannels.reminded, false),
        isNull(applicationChannels.delete_after),
        lte(applicationChannels.created_at, createdBefore),
      ),
    );
}

/**
 * Live application channels (no decision yet) created before `createdBefore`
 * (unix seconds). Used to sweep channels of applicants who never submitted.
 */
export async function getApplicationChannelsOlderThan(
  createdBefore: number,
): Promise<ApplicationChannel[]> {
  return db
    .select()
    .from(applicationChannels)
    .where(
      and(
        isNull(applicationChannels.delete_after),
        lte(applicationChannels.created_at, createdBefore),
      ),
    );
}

/** Channels whose post-decision deletion window has elapsed. */
export async function getExpiredApplicationChannels(
  now: number,
): Promise<ApplicationChannel[]> {
  return db
    .select()
    .from(applicationChannels)
    .where(lte(applicationChannels.delete_after, now));
}

export async function deleteApplicationChannelRow(
  channelId: string,
): Promise<void> {
  await db
    .delete(applicationChannels)
    .where(eq(applicationChannels.channel_id, channelId));
}
