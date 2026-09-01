import posthog from "posthog-js";

const projectToken = process.env.NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN;

if (projectToken) {
  try {
    posthog.init(projectToken, {
      api_host:
        process.env.NEXT_PUBLIC_POSTHOG_HOST ?? "https://eu.i.posthog.com",
      defaults: "2026-05-30",
      autocapture: false,
      capture_exceptions: false,
      ip: false,
      mask_all_element_attributes: true,
      mask_all_text: true,
      person_profiles: "identified_only",
      respect_dnt: true,
      session_recording: {
        maskAllInputs: true,
        maskTextSelector: "*",
      },
      disable_session_recording:
        process.env.NEXT_PUBLIC_POSTHOG_SESSION_REPLAY !== "true",
    });
  } catch {
    console.warn("PostHog analytics could not be initialised.");
  }
}
