"use client";

import posthog from "posthog-js";
import {
  AnalyticsEvent,
  type AnalyticsEventName,
  type AnalyticsProperties,
} from "@crabcraft/shared/analytics";

const completedTools = new Set<string>();

export function captureWebEvent(
  event: AnalyticsEventName,
  properties: AnalyticsProperties = {},
): void {
  if (!process.env.NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN) return;
  try {
    posthog.capture(event, {
      ...properties,
      environment:
        process.env.NEXT_PUBLIC_POSTHOG_ENVIRONMENT ??
        process.env.NODE_ENV ??
        "production",
      source: "website",
    });
  } catch {
    // Analytics must never interfere with a website action.
  }
}

export function captureWebToolCompleted(
  tool: string,
  action: string,
  properties: AnalyticsProperties = {},
): void {
  if (completedTools.has(tool)) return;
  completedTools.add(tool);
  captureWebEvent(AnalyticsEvent.WEB_TOOL_COMPLETED, {
    ...properties,
    action,
    tool,
  });
}

export function isWebFeatureEnabled(flag: string, fallback = false): boolean {
  if (!process.env.NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN) return fallback;
  try {
    return posthog.isFeatureEnabled(flag) ?? fallback;
  } catch {
    return fallback;
  }
}
