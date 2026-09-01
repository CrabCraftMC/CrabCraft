import { createHmac } from "node:crypto";
import { canonicalMinecraftUuid } from "./analytics";

/**
 * Produces the stable, pseudonymous PostHog distinct ID used across CrabCraft.
 * The salt must be secret and identical in the web, bot, and Velocity configs.
 */
export function minecraftAnalyticsId(
  minecraftUuid: string,
  identitySalt: string,
): string | null {
  const canonicalUuid = canonicalMinecraftUuid(minecraftUuid);
  const salt = identitySalt.trim();
  if (!canonicalUuid || !salt) return null;

  return `cc_${createHmac("sha256", salt)
    .update(`minecraft:${canonicalUuid}`)
    .digest("hex")}`;
}
