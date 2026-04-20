import Event from "../structures/Event.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { TextChannel, MessageFlags, ChannelType, type GuildMember } from "discord.js";
import { logMemberLeft } from "../utils/embeds.js";

import mysql from "../utils/database.js";
import * as appDb from "../utils/appDb.js";
import { fetchPlayerName } from "../utils/mojang.js";
import { saveTranscriptToLog } from "../utils/transcript.js";

export default class MemberLeftEvent extends Event {
  constructor() {
    super("MemberLeft", "guildMemberRemove", false);
  }

  async execute(member: GuildMember) {
    if (member.user.bot) return;

    // 1. Delete application channel (search category children by topic)
    try {
      const category = member.guild.channels.cache.get(config.APPLICATION_CATEGORY_ID);
      if (category?.type === ChannelType.GuildCategory) {
        // Use cached children — these are kept up to date by the gateway
        const children = category.children.cache;
        let channel = children.find(
          (ch) =>
            ch.isTextBased() &&
            (ch as TextChannel).topic?.split("|")[0] === member.id,
        ) as TextChannel | undefined;

        // Fallback: match by channel name if topic lookup fails
        if (!channel) {
          channel = children.find(
            (ch) =>
              ch.isTextBased() &&
              ch.name === `app-${member.user.username}`,
          ) as TextChannel | undefined;
        }

        if (channel) {
          const logCh = await member.guild.channels
            .fetch(config.LOG_CHANNEL_ID)
            .catch(() => null) as TextChannel | null;
          if (logCh) {
            await saveTranscriptToLog(channel, logCh, `member ${member.user.tag} left`).catch(() => null);
          }
          await channel.delete();
        }
      }
    } catch (error) {
      logger.error("Failed to delete application channel for departing member:", error);
    }

    // 2. Remove whitelist entry from MariaDB
    let rows: any[] = [];
    try {
      rows = await mysql.query(
        "SELECT * FROM discordsrv_accounts WHERE discord = ?",
        [member.id],
      );

      if (rows.length === 0) {
        // Not whitelisted - still update Turso state below
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

    // 4. Update Turso state
    try {
      await appDb.cancelPendingApplications(member.user.id);
    } catch (error) {
      logger.error("Failed to update Turso for departing member:", error);
    }
  }
}
