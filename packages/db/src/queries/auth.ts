import { eq, sql } from "drizzle-orm";
import { db } from "../client";
import { players } from "../schema";

export async function getUserForAuth(
  discordId: string,
): Promise<{
  minecraft_uuid: string | null;
  minecraft_username: string | null;
  role: string;
} | null> {
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
      role: players.role,
    })
    .from(players)
    .where(eq(players.discord_id, discordId));
  if (rows.length === 0) return null;
  return rows[0];
}

export async function updateOnLogin(
  discordId: string,
  discordUsername: string,
  minecraftUsername: string | null,
): Promise<void> {
  await db
    .update(players)
    .set({
      last_login_at: Math.floor(Date.now() / 1000),
      discord_username: discordUsername,
      ...(minecraftUsername ? { minecraft_username: minecraftUsername } : {}),
      updated_at: Math.floor(Date.now() / 1000),
    })
    .where(eq(players.discord_id, discordId));
}
