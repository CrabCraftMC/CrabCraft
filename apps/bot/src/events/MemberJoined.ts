import Event from "../structures/Event.js";
import { openApplicationThread } from "../utils/applicationThread.js";
import type { GuildMember } from "discord.js";

export default class MemberJoinedEvent extends Event {
  constructor() {
    super("MemberJoined", "guildMemberAdd", false);
  }

  async execute(member: GuildMember) {
    if (member.user.bot) return;

    // Open (or reuse) the applicant's private application thread under the
    // configured application channel. The helper grants access, adds + pings
    // the applicant, and posts the welcome message with the Apply button.
    await openApplicationThread(member);
  }
}
