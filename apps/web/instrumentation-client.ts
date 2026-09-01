import posthog from "posthog-js";
import {
  sanitiseAnalyticsUrl,
  sanitisePostHogEvent,
} from "@/lib/analyticsPrivacy";

const projectToken = process.env.NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN;

if (projectToken) {
  try {
    posthog.init(projectToken, {
      api_host:
        process.env.NEXT_PUBLIC_POSTHOG_HOST ?? "https://eu.i.posthog.com",
      defaults: "2026-05-30",
      autocapture: false,
      before_send: sanitisePostHogEvent,
      capture_exceptions: false,
      get_current_url: (defaultUrl) =>
        sanitiseAnalyticsUrl(defaultUrl) ?? "https://crabcraft.net/",
      ip: false,
      mask_all_element_attributes: true,
      mask_all_text: true,
      mask_personal_data_properties: true,
      person_profiles: "identified_only",
      respect_dnt: true,
      save_campaign_params: false,
      session_recording: {
        maskAllInputs: true,
        maskCapturedNetworkRequestFn: () => null,
        maskTextSelector: "*",
      },
      disable_session_recording:
        process.env.NEXT_PUBLIC_POSTHOG_SESSION_REPLAY !== "true",
    });
  } catch {
    console.warn("PostHog analytics could not be initialised.");
  }
}
