import { PostHog } from "posthog-node";
import { createHash } from "node:crypto";
import {
  type AnalyticsEventName,
  type AnalyticsProperties,
} from "@crabcraft/shared/analytics";
import { minecraftAnalyticsId } from "@crabcraft/shared/analytics-identity";
import config from "./config.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";

const configured = Boolean(
  config.POSTHOG_PROJECT_TOKEN && config.POSTHOG_PERSON_SALT,
);

if (
  Boolean(config.POSTHOG_PROJECT_TOKEN) !== Boolean(config.POSTHOG_PERSON_SALT)
) {
  logger.warn(
    "PostHog analytics disabled: POSTHOG_PROJECT_TOKEN and POSTHOG_PERSON_SALT must be configured together.",
  );
}

function createClient(): PostHog | null {
  if (!configured) return null;
  try {
    return new PostHog(config.POSTHOG_PROJECT_TOKEN, {
      host: config.POSTHOG_HOST,
      flushAt: 20,
      flushInterval: 10_000,
      requestTimeout: 3_000,
      privacyMode: true,
    });
  } catch (error) {
    logger.warn(`PostHog analytics disabled: ${String(error)}`);
    return null;
  }
}

const client = createClient();
const pendingIdentityLookups = new Set<Promise<void>>();

export interface AnalyticsCaptureOptions {
  dedupeKey?: string;
}

export function captureMinecraftEvent(
  minecraftUuid: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties = {},
  options: AnalyticsCaptureOptions = {},
): void {
  if (!client) return;
  const distinctId = minecraftAnalyticsId(
    minecraftUuid,
    config.POSTHOG_PERSON_SALT,
  );
  if (!distinctId) return;

  try {
    const insertId = options.dedupeKey
      ? createHash("sha256")
          .update(`${distinctId}:${event}:${options.dedupeKey}`)
          .digest("hex")
      : null;
    client.capture({
      distinctId,
      event,
      disableGeoip: true,
      properties: {
        ...properties,
        source: "discord_bot",
        environment: config.POSTHOG_ENVIRONMENT,
        $process_person_profile: false,
        ...(insertId ? { $insert_id: insertId } : {}),
      },
    });
  } catch (error) {
    logger.debug(`PostHog capture failed: ${String(error)}`);
  }
}

async function captureLinkedDiscordEvent(
  discordId: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties = {},
  options: AnalyticsCaptureOptions = {},
): Promise<void> {
  if (!client) return;
  try {
    const minecraftUuid = await appDb.getMinecraftUuidByDiscordId(discordId);
    if (minecraftUuid) {
      captureMinecraftEvent(minecraftUuid, event, properties, options);
    }
  } catch (error) {
    logger.debug(`PostHog identity lookup failed: ${String(error)}`);
  }
}

export function captureDiscordEvent(
  discordId: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties = {},
  options: AnalyticsCaptureOptions = {},
): Promise<void> {
  const pending = captureLinkedDiscordEvent(
    discordId,
    event,
    properties,
    options,
  );
  pendingIdentityLookups.add(pending);
  void pending.then(
    () => pendingIdentityLookups.delete(pending),
    () => pendingIdentityLookups.delete(pending),
  );
  return pending;
}

export async function shutdownAnalytics(): Promise<void> {
  if (!client) return;
  try {
    await Promise.allSettled([...pendingIdentityLookups]);
    await client.shutdown(5_000);
  } catch (error) {
    logger.warn(`PostHog did not shut down cleanly: ${String(error)}`);
  }
}
