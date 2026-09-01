import Event from "../structures/Event.js";
import { createApplicationChannelFor } from "../utils/applicationChannel.js";
import type { GuildMember } from "discord.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import * as appDb from "../utils/appDb.js";

export default class MemberJoinedEvent extends Event {
  constructor() {
    super("MemberJoined", "guildMemberAdd", false);
  }

  async execute(member: GuildMember) {
    if (member.user.bot) return;

    if (member.guild.id === config.GUILD_ID) {
      try {
        await appDb.setPlayerDiscordMembership(member.id, true);
      } catch (error) {
        logger.error("Failed to restore returning member's guild status:", error);
      }
    }

    // Create (or reuse) the applicant's private application channel under the
    // configured category. The helper records the channel, configures access,
    // pings the applicant, and posts the welcome message with the Apply button.
    await createApplicationChannelFor(member);
  }
}
