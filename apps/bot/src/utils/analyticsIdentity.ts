import { canonicalMinecraftUuid } from "@crabcraft/shared/analytics";
import { minecraftAnalyticsId } from "@crabcraft/shared/analytics-identity";

export interface LinkedAnalyticsIdentity {
  discord_id: string;
  discord_username: string;
  minecraft_uuid: string;
  minecraft_username: string | null;
  nickname: string | null;
  role: string;
}

function dashedMinecraftUuid(uuid: string): string | null {
  const canonical = canonicalMinecraftUuid(uuid);
  if (!canonical) return null;
  return [
    canonical.slice(0, 8),
    canonical.slice(8, 12),
    canonical.slice(12, 16),
    canonical.slice(16, 20),
    canonical.slice(20),
  ].join("-");
}

export function analyticsPerson(
  minecraftUuid: string,
  personSalt: string,
  identity: LinkedAnalyticsIdentity | null,
): { distinctId: string; properties: Record<string, string> } | null {
  const distinctId = minecraftAnalyticsId(minecraftUuid, personSalt);
  const uuid = dashedMinecraftUuid(minecraftUuid);
  if (!distinctId || !uuid) return null;

  const properties: Record<string, string> = { minecraft_uuid: uuid };
  if (identity) {
    properties.discord_id = identity.discord_id;
    properties.discord_username = identity.discord_username;
    properties.role = identity.role;
    if (identity.nickname) properties.minecraft_nickname = identity.nickname;
    if (identity.minecraft_username) {
      properties.name = identity.minecraft_username;
      properties.minecraft_username = identity.minecraft_username;
    }
  }

  return { distinctId, properties };
}
