import type { Guild } from "discord.js";
import * as appDb from "./appDb.js";
import logger from "./logger.js";

/**
 * Fetch the complete guild roster before changing PostgreSQL, then atomically
 * replace the stored player-membership snapshot. If Discord fetch fails, the
 * existing snapshot is left untouched.
 */
export async function syncPlayerDiscordMembership(guild: Guild): Promise<void> {
  const guildMembers = await guild.members.fetch();
  const discordIds = Array.from(guildMembers.values())
    .filter((member) => !member.user.bot)
    .map((member) => member.id);

  const counts = await appDb.reconcilePlayerDiscordMembership(discordIds);
  logger.info(
    `Discord membership reconcile complete: ${counts.members} current players, ${counts.nonMembers} departed players excluded`,
  );
}
