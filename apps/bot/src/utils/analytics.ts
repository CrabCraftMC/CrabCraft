import { PostHog } from "posthog-node";
import { createHash } from "node:crypto";
import {
  type AnalyticsEventName,
  type AnalyticsProperties,
} from "@crabcraft/shared/analytics";
import { analyticsPerson, type LinkedAnalyticsIdentity } from "./analyticsIdentity.js";
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

function captureEvent(
  minecraftUuid: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties,
  options: AnalyticsCaptureOptions,
  identity: LinkedAnalyticsIdentity | null,
): void {
  if (!client) return;
  const person = analyticsPerson(
    minecraftUuid,
    config.POSTHOG_PERSON_SALT,
    identity,
  );
  if (!person) return;

  try {
    const insertId = options.dedupeKey
      ? createHash("sha256")
          .update(`${person.distinctId}:${event}:${options.dedupeKey}`)
          .digest("hex")
      : null;
    client.capture({
      distinctId: person.distinctId,
      event,
      // A bot event otherwise resolves to the bot host, not the player.
      disableGeoip: true,
      properties: {
        ...properties,
        source: "discord_bot",
        environment: config.POSTHOG_ENVIRONMENT,
        $set: person.properties,
        ...(insertId ? { $insert_id: insertId } : {}),
      },
    });
  } catch (error) {
    logger.debug(`PostHog capture failed: ${String(error)}`);
  }
}

function trackIdentityLookup(pending: Promise<void>): void {
  pendingIdentityLookups.add(pending);
  void pending.then(
    () => pendingIdentityLookups.delete(pending),
    () => pendingIdentityLookups.delete(pending),
  );
}

async function captureMinecraftEventWithIdentity(
  minecraftUuid: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties,
  options: AnalyticsCaptureOptions,
): Promise<void> {
  if (!client) return;
  let identity: LinkedAnalyticsIdentity | null = null;
  try {
    identity = await appDb.getPlayerByMinecraftUuid(minecraftUuid);
  } catch (error) {
    logger.debug(`PostHog identity lookup failed: ${String(error)}`);
  }
  captureEvent(minecraftUuid, event, properties, options, identity);
}

export function captureMinecraftEvent(
  minecraftUuid: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties = {},
  options: AnalyticsCaptureOptions = {},
): void {
  trackIdentityLookup(
    captureMinecraftEventWithIdentity(
      minecraftUuid,
      event,
      properties,
      options,
    ),
  );
}

async function captureLinkedDiscordEvent(
  discordId: string,
  event: AnalyticsEventName,
  properties: AnalyticsProperties = {},
  options: AnalyticsCaptureOptions = {},
): Promise<void> {
  if (!client) return;
  try {
    const identity = await appDb.getPlayerByDiscordId(discordId);
    if (identity) {
      captureEvent(identity.minecraft_uuid, event, properties, options, identity);
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
  trackIdentityLookup(pending);
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
