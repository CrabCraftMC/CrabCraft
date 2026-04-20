import { eq, sql } from "drizzle-orm";
import { db } from "../client";
import { users } from "../schema";

export async function getUserForAuth(
  discordId: string,
): Promise<{
  minecraft_uuid: string | null;
  minecraft_username: string | null;
  is_admin: boolean;
} | null> {
  const rows = await db
    .select({
      minecraft_uuid: users.minecraft_uuid,
      minecraft_username: users.minecraft_username,
      is_admin: users.is_admin,
    })
    .from(users)
    .where(eq(users.discord_id, discordId));
  if (rows.length === 0) return null;
  return rows[0];
}

export async function updateLastLogin(discordId: string): Promise<void> {
  await db
    .update(users)
    .set({ last_login_at: Math.floor(Date.now() / 1000) })
    .where(eq(users.discord_id, discordId));
}
