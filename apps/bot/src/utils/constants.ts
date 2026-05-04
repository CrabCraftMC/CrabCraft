/** How long a retry session lasts before expiring (15 minutes). */
export const RETRY_EXPIRY_MS = 15 * 60 * 1000;

/** Delay before auto-deleting an application channel after accept/deny (12 hours). */
export const CHANNEL_DELETE_DELAY_MS = 12 * 60 * 60 * 1000;

/** Interval between leaderboard background refreshes (5 minutes). */
export const LEADERBOARD_REFRESH_MS = 5 * 60 * 1000;

/** How long before an inactive applicant gets a reminder (1 day). */
export const APPLICATION_REMINDER_DELAY_MS = 1 * 24 * 60 * 60 * 1000;

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

/** Delay before auto-deleting a closed ticket thread (24 hours). */
export const TICKET_DELETE_DELAY_MS = 24 * 60 * 60 * 1000;

/** Interval between scans for expired closed tickets (15 minutes). */
export const TICKET_CLEANUP_INTERVAL_MS = 15 * 60 * 1000;
