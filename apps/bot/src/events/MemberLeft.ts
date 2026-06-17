import Event from "../structures/Event.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { TextChannel, MessageFlags, type GuildMember } from "discord.js";
import { logMemberLeft } from "../utils/embeds.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import { fetchPlayerName } from "../utils/mojang.js";
import { finalizeApplicationChannel } from "../utils/applicationChannel.js";
import { deleteAllAltsForUser } from "../utils/altDb.js";

export default class MemberLeftEvent extends Event {
  constructor() {
    super("MemberLeft", "guildMemberRemove", false);
  }

  async execute(member: GuildMember) {
    if (member.user.bot) return;

    // 1. Tear down the applicant's application channel (save a transcript
    //    first, then delete the channel). Skip the transcript if the
    //    application was already resolved (accept/deny already saved one).
    try {
      const row = await appDb
        .getApplicationChannelByApplicant(member.id)
        .catch(() => null);
      if (row) {
        await finalizeApplicationChannel(
          member.guild,
          row,
          row.delete_after ? null : `member ${member.user.tag} left`,
        );
      }
    } catch (error) {
      logger.error("Failed to tear down application channel for departing member:", error);
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
            components: [logMemberLeft(member.user.tag, playerName)],
            flags: MessageFlags.IsComponentsV2,
          });
        }
      } catch (error) {
        logger.error("Failed to log departing member:", error);
      }
    }

    // 4. Cancel any pending applications
    try {
      await appDb.cancelPendingApplications(member.user.id);
    } catch (error) {
      logger.error("Failed to cancel pending applications for departing member:", error);
    }

    // 5. Remove alt accounts
    try {
      await deleteAllAltsForUser(member.user.id);
    } catch (error) {
      logger.error("Failed to remove alt accounts for departing member:", error);
    }
  }
}
