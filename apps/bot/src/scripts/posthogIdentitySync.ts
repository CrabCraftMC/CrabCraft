import { PostHog } from "posthog-node";
import { analyticsPerson } from "../utils/analyticsIdentity.js";

function usage(): string {
  return [
    "Usage: bun run analytics:sync-identities -- [--dry-run]",
    "",
    "Creates or updates PostHog person profiles for every linked player.",
    "No gameplay events are generated.",
  ].join("\n");
}

async function main(): Promise<void> {
  const args = new Set(process.argv.slice(2));
  if (args.has("--help")) {
    console.info(usage());
    return;
  }
  const unknown = [...args].filter((arg) => arg !== "--dry-run");
  if (unknown.length > 0) throw new Error(`Unknown option: ${unknown[0]}`);

  const [
    { closeDatabase },
    { getAllPlayerAnalyticsIdentities },
    { default: config },
  ] = await Promise.all([
    import("@crabcraft/db/client"),
    import("../utils/appDb.js"),
    import("../utils/config.js"),
  ]);

  let client: PostHog | null = null;
  try {
    if (!config.POSTHOG_PROJECT_TOKEN || !config.POSTHOG_PERSON_SALT) {
      throw new Error(
        "POSTHOG_PROJECT_TOKEN and POSTHOG_PERSON_SALT must be configured.",
      );
    }

    const identities = await getAllPlayerAnalyticsIdentities();
    if (args.has("--dry-run")) {
      console.info(`Would sync ${identities.length} linked PostHog identities.`);
      return;
    }

    client = new PostHog(config.POSTHOG_PROJECT_TOKEN, {
      host: config.POSTHOG_HOST,
      flushAt: 100,
      flushInterval: 1_000,
      requestTimeout: 5_000,
    });
    for (const identity of identities) {
      const person = analyticsPerson(
        identity.minecraft_uuid,
        config.POSTHOG_PERSON_SALT,
        identity,
      );
      if (!person) continue;
      client.identify({
        distinctId: person.distinctId,
        properties: person.properties,
        // The process host is not the player and must not overwrite their GeoIP.
        disableGeoip: true,
      });
    }
    await client.shutdown(10_000);
    client = null;
    console.info(`Synced ${identities.length} linked PostHog identities.`);
  } finally {
    if (client) await client.shutdown(10_000);
    await closeDatabase();
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error(`[analytics] Identity sync failed: ${String(error)}`);
    console.error(usage());
    process.exitCode = 1;
  });
}
