export interface WebAnalyticsUser {
  discordId: string;
  discordUsername?: string | null;
  minecraftUuid: string;
  minecraftUsername?: string | null;
  minecraftNickname?: string | null;
  role: string;
}

export interface WebAnalyticsIdentity {
  distinctId: string;
  properties: Record<string, string>;
}

export function buildWebAnalyticsIdentity(
  distinctId: string | null,
  user: WebAnalyticsUser | null,
): WebAnalyticsIdentity | null {
  if (!distinctId || !user) return null;

  return {
    distinctId,
    properties: {
      discord_id: user.discordId,
      ...(user.discordUsername
        ? { discord_username: user.discordUsername }
        : {}),
      minecraft_uuid: user.minecraftUuid.toLowerCase(),
      ...(user.minecraftUsername
        ? {
            name: user.minecraftUsername,
            minecraft_username: user.minecraftUsername,
          }
        : {}),
      ...(user.minecraftNickname
        ? { minecraft_nickname: user.minecraftNickname }
        : {}),
      role: user.role,
    },
  };
}
