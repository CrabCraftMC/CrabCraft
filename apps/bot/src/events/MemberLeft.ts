import Event from "../structures/Event.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { TextChannel, type GuildMember } from "discord.js";
import { logMemberLeft } from "../utils/embeds.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import { fetchPlayerName } from "../utils/mojang.js";
import { finalizeApplicationChannel } from "../utils/applicationChannel.js";
import { deleteAllAltsForUser } from "../utils/appDb.js";

export default class MemberLeftEvent extends Event {
  constructor() {
    super("MemberLeft", "guildMemberRemove", false);
  }

  async execute(member: GuildMember) {
    if (member.user.bot) return;
    if (member.guild.id !== config.GUILD_ID) return;

    // Keep their identity and historical stats, but immediately remove them
    // from all community leaderboards and award rankings.
    try {
      await appDb.setPlayerDiscordMembership(member.id, false);
    } catch (error) {
      logger.error("Failed to mark departing member as outside the guild:", error);
    }

    // 1. Find the applicant's application channel. Teardown happens after the
    //    departure log so any transcript file appears beneath the log message.
    let applicationChannelRow: Awaited<
      ReturnType<typeof appDb.getApplicationChannelByApplicant>
    > | null = null;
    try {
      applicationChannelRow = await appDb
        .getApplicationChannelByApplicant(member.id)
        .catch(() => null);
    } catch (error) {
      logger.error("Failed to find application channel for departing member:", error);
    }

    // 2. Remove whitelist entry from MariaDB
    let rows: any[] = [];
    try {
      rows = await mysql.query(
        "SELECT * FROM discordsrv_accounts WHERE discord = ?",
        [member.id],
      );

      if (rows.length === 0) {
        // Not whitelisted - still cancel pending applications below
      } else {
        await mysql.query(
          "DELETE FROM discordsrv_accounts WHERE discord = ?",
          [member.id],
        );
      }
    } catch (error) {
      logger.error("Failed to clean up MariaDB for departing member:", error);
    }

    // 3. Log departure (only if they were whitelisted)
    if (rows.length > 0) {
      try {
        const playerName = await fetchPlayerName(rows[0].uuid);
        const logChannel = await member.guild.channels
          .fetch(config.LOG_CHANNEL_ID)
          .catch(() => null) as TextChannel | null;

        if (logChannel) {
          await logChannel.send({
            content: logMemberLeft(`<@${member.id}>`, playerName),
          });
        }
      } catch (error) {
        logger.error("Failed to log departing member:", error);
      }
    }

    // 4. Tear down the applicant's application channel. Skip the transcript if
    //    the application was already resolved (accept/deny already saved one).
    if (applicationChannelRow) {
      await finalizeApplicationChannel(
        member.guild,
        applicationChannelRow,
        !applicationChannelRow.delete_after,
      );
    }

    // 5. Cancel any pending applications
    try {
      await appDb.cancelPendingApplications(member.user.id);
    } catch (error) {
      logger.error("Failed to cancel pending applications for departing member:", error);
    }

    // 6. Remove alt accounts
    try {
      await deleteAllAltsForUser(member.user.id);
    } catch (error) {
      logger.error("Failed to remove alt accounts for departing member:", error);
    }
  }
}
