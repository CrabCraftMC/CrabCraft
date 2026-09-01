import { describe, expect, test } from "bun:test";

process.env.GALLERY_MEDIA_BASE_URL = "https://cdn.crabcraft.net";

const { default: nextConfig } = await import("../next.config");

describe("web security headers", () => {
  test("allows PostHog Cloud EU without retaining the old analytics host", async () => {
    const configuredHeaders = await nextConfig.headers?.();
    const pageHeaders = configuredHeaders?.find(
      (entry) => entry.source === "/(.*)",
    );
    const csp = pageHeaders?.headers.find(
      (header) => header.key === "Content-Security-Policy",
    )?.value;

    expect(csp).toContain(
      "script-src 'self' 'unsafe-inline' https://eu-assets.i.posthog.com",
    );
    expect(csp).toContain(
      "connect-src 'self' https://api.crabcraft.net https://eu.i.posthog.com https://eu-assets.i.posthog.com",
    );
    expect(csp).not.toContain("web.maxmoon.sh");
  });
});
