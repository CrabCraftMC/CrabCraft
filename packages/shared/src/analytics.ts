/**
 * Canonical PostHog event names shared by the website and Discord bot.
 *
 * Event names deliberately follow PostHog's recommended "object verb" style.
 * Properties use snake_case so the equivalent Java events have exactly the
 * same schema.
 */
export const AnalyticsEvent = {
  APPLICATION_SUBMITTED: "application submitted",
  APPLICATION_RESOLVED: "application resolved",
  BINGO_SQUARE_COMPLETED: "bingo square completed",
  DISCORD_COMMAND_COMPLETED: "discord command completed",
  GALLERY_POST_PUBLISHED: "gallery post published",
  PLAYER_JOINED: "player joined",
  PLAYER_SESSION_ENDED: "player session ended",
  SERVER_SWITCHED: "server switched",
  LOGIN_DAY_QUALIFIED: "login day qualified",
  PLAYER_SETTING_CHANGED: "player setting changed",
  SERVER_ADDRESS_COPIED: "server address copied",
  WEB_TOOL_COMPLETED: "web tool completed",
  WRAPPED_COMPLETED: "wrapped completed",
} as const;

export type AnalyticsEventName =
  (typeof AnalyticsEvent)[keyof typeof AnalyticsEvent];

export type AnalyticsProperty = string | number | boolean | null;
export type AnalyticsProperties = Record<string, AnalyticsProperty>;

/** Canonical UUID representation used as HMAC input by every runtime. */
export function canonicalMinecraftUuid(uuid: string): string | null {
  const canonical = uuid.trim().toLowerCase().replaceAll("-", "");
  return /^[0-9a-f]{32}$/.test(canonical) ? canonical : null;
}
