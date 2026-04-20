/** Insert dashes into a raw Mojang UUID. */
export function formatUUID(rawId: string): string {
  return `${rawId.slice(0, 8)}-${rawId.slice(8, 12)}-${rawId.slice(12, 16)}-${rawId.slice(16, 20)}-${rawId.slice(20)}`;
}

/** Check if a string is a valid Minecraft Java username (3-16 alphanumeric + underscore). */
export function isValidUsername(username: string): boolean {
  return /^[a-zA-Z0-9_]{3,16}$/.test(username);
}

/** Validate username format + Mojang lookup. Returns formatted UUID and name on success, null on failure. */
export async function resolveUsername(
  username: string,
): Promise<{ uuid: string; name: string } | null> {
  if (!isValidUsername(username)) return null;

  try {
    const res = await fetch(
      `https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`,
    );
    if (!res.ok) return null;
    const data = (await res.json()) as Record<string, string>;
    if ("errorMessage" in data || !data["id"]) return null;

    return { uuid: formatUUID(data["id"]), name: data["name"] };
  } catch (error) {
    console.error("Failed to fetch Mojang profile:", error);
    return null;
  }
}

/** Fetch a player's current name from their UUID via the Mojang session server. */
export async function fetchPlayerName(uuid: string): Promise<string> {
  try {
    const res = await fetch(
      `https://sessionserver.mojang.com/session/minecraft/profile/${uuid}`,
    );
    if (res.ok) {
      const player = (await res.json()) as Record<string, string>;
      if (player["name"]) return player["name"];
    }
  } catch (error) {
    console.error("Failed to fetch Mojang profile:", error);
  }
  return "Unknown";
}
