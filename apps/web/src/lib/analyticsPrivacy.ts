import type { CaptureResult, Properties } from "posthog-js";

const CRABCRAFT_HOST_PATTERN = /(^|\.)crabcraft\.net$/i;
const CAMPAIGN_PROPERTY_PATTERN = /^(\$)?(utm_|gclid$|dclid$|fbclid$|msclkid$|twclid$|li_fat_id$|gbraid$|wbraid$)/i;

export function sanitiseAnalyticsPath(pathname: string): string {
  const normalised = pathname.startsWith("/") ? pathname : "/";
  if (/^\/stats\/[^/]+/.test(normalised)) return "/stats/[player]";
  if (/^\/gallery\/[^/]+/.test(normalised)) return "/gallery/[post]";
  return normalised;
}

export function sanitiseAnalyticsUrl(
  rawUrl: unknown,
  referrer = false,
): string | null {
  if (typeof rawUrl !== "string" || !rawUrl.trim()) return null;
  try {
    const url = new URL(rawUrl, "https://crabcraft.net");
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    if (referrer || !CRABCRAFT_HOST_PATTERN.test(url.hostname)) {
      return url.origin;
    }
    return `${url.origin}${sanitiseAnalyticsPath(url.pathname)}`;
  } catch {
    return null;
  }
}

function sanitiseProperties(properties: Properties): Properties {
  const sanitised: Properties = {};
  for (const [key, value] of Object.entries(properties)) {
    const lowerKey = key.toLowerCase();
    if (lowerKey === "title" || lowerKey === "$title") continue;
    if (CAMPAIGN_PROPERTY_PATTERN.test(lowerKey)) continue;

    if (lowerKey.includes("pathname")) {
      if (typeof value === "string") {
        sanitised[key] = sanitiseAnalyticsPath(value);
      }
      continue;
    }

    if (lowerKey.includes("url") || lowerKey.includes("referrer")) {
      const safeUrl = sanitiseAnalyticsUrl(
        value,
        lowerKey.includes("referrer"),
      );
      if (safeUrl) sanitised[key] = safeUrl;
      continue;
    }

    sanitised[key] = value;
  }
  return sanitised;
}

export function sanitisePostHogEvent(
  event: CaptureResult | null,
): CaptureResult | null {
  if (!event) return null;
  return {
    ...event,
    properties: sanitiseProperties(event.properties),
    ...(event.$set ? { $set: sanitiseProperties(event.$set) } : {}),
    ...(event.$set_once
      ? { $set_once: sanitiseProperties(event.$set_once) }
      : {}),
  };
}
