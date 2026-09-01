"use client";

import { useEffect } from "react";
import posthog from "posthog-js";

const IDENTIFIED_MARKER = "crabcraft-posthog-identified";

export default function AnalyticsIdentity({
  analyticsId,
}: {
  analyticsId: string | null;
}) {
  useEffect(() => {
    if (!process.env.NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN) return;

    try {
      const distinctId = posthog.get_distinct_id();
      const currentUserId =
        posthog.get_property("$user_id") ??
        (distinctId.startsWith("cc_") ? distinctId : null);
      if (analyticsId) {
        if (currentUserId && currentUserId !== analyticsId) posthog.reset();
        if (currentUserId !== analyticsId) posthog.identify(analyticsId);
        localStorage.setItem(IDENTIFIED_MARKER, "true");
      } else if (
        currentUserId ||
        localStorage.getItem(IDENTIFIED_MARKER) === "true"
      ) {
        posthog.reset();
        localStorage.removeItem(IDENTIFIED_MARKER);
      }
    } catch {
      // Identity analytics must not interfere with rendering or authentication.
    }
  }, [analyticsId]);

  return null;
}
