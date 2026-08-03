/** How long a retry session lasts before expiring (15 minutes). */
export const RETRY_EXPIRY_MS = 15 * 60 * 1000;

/** Delay before auto-deleting an application channel after accept/deny (12 hours). */
export const CHANNEL_DELETE_DELAY_MS = 12 * 60 * 60 * 1000;

/** Interval between leaderboard background refreshes (5 minutes). */
export const LEADERBOARD_REFRESH_MS = 5 * 60 * 1000;

/** Interval between Discord/Minecraft username syncs (24 hours). */
export const IDENTITY_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000;

/** Interval between complete Discord gallery reconciliations (6 hours). */
export const GALLERY_RECONCILE_INTERVAL_MS = 6 * 60 * 60 * 1000;

/** Short delays used while Discord finishes creating a thread starter message. */
export const GALLERY_STARTER_RETRY_DELAYS_MS = [0, 250, 1_000, 3_000] as const;

/** Bounded waits while cleanup finishes deleting a deterministic media key. */
export const GALLERY_STORAGE_WRITE_RETRY_DELAYS_MS = [
  250,
  1_000,
  3_000,
  6_000,
] as const;

/** Maximum number of gallery posts processed concurrently during a full scan. */
export const GALLERY_SYNC_CONCURRENCY = 3;

/** Interval between durable Gallery media-deletion queue polls (1 minute). */
export const GALLERY_STORAGE_DELETE_INTERVAL_MS = 60 * 1_000;

/** Maximum Gallery media deletions claimed in one queue poll. */
export const GALLERY_STORAGE_DELETE_BATCH_SIZE = 25;

/** Lease used to keep Gallery media deletion work exclusive (5 minutes). */
export const GALLERY_STORAGE_DELETE_LEASE_SECONDS = 5 * 60;

/** Fallback interval between full Minecraft ban/mute role reconciliations (5 minutes). */
export const PUNISHMENT_ROLE_SYNC_INTERVAL_MS = 5 * 60 * 1000;

/** Interval between Discord bot player-count status refreshes (1 minute). */
export const BOT_STATUS_REFRESH_MS = 60 * 1000;

/** How long before an inactive applicant gets a reminder (1 day). */
export const APPLICATION_REMINDER_DELAY_MS = 1 * 24 * 60 * 60 * 1000;

/**
 * How long an application channel can sit with no application submitted
 * before it's deleted (5 days). The member stays in the server and can open
 * a new channel from the application hub.
 */
export const APPLICATION_INACTIVE_DELETE_MS = 5 * 24 * 60 * 60 * 1000;

/** Interval between application reminder scans (30 minutes). */
export const APPLICATION_REMINDER_CHECK_MS = 30 * 60 * 1000;

/** Interval between wiki recent changes polls (15 minutes). */
export const WIKI_POLL_MS = 15 * 60 * 1000;

/** Interval between YouTube RSS feed polls (2 minutes). */
export const YOUTUBE_RSS_POLL_MS = 2 * 60 * 1000;

/** Interval between YouTube live status checks for active streams (1 minute). */
export const YOUTUBE_LIVE_CHECK_MS = 60 * 1000;

/** Interval between Twitch stream status polls (1 minute). */
export const TWITCH_POLL_MS = 60 * 1000;

/** Interval between TikTok live status polls (2 minutes). */
export const TIKTOK_POLL_MS = 2 * 60 * 1000;

/** Delay before auto-deleting a closed ticket channel (24 hours). */
export const TICKET_DELETE_DELAY_MS = 24 * 60 * 60 * 1000;

/** Interval between scans for expired closed tickets (15 minutes). */
export const TICKET_CLEANUP_INTERVAL_MS = 15 * 60 * 1000;

/** Number of unique non-author reactors required to repost a message to the starboard. */
export const STARBOARD_THRESHOLD = 5;

/** How long to wait after the last reaction change before editing the starboard repost (30s). */
export const STARBOARD_UPDATE_DEBOUNCE_MS = 30 * 1000;

/** Custom emoji the bot reacts with when a message mentions "crab". */
export const CRAB_EMOJI_ID = "1397355651822256299";
