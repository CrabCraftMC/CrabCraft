import Event from "../structures/Event.js";
import type { DMChannel, GuildChannel } from "discord.js";
import logger from "../utils/logger.js";
import * as appDb from "../utils/appDb.js";

/**
 * When a channel is deleted directly in Discord (rather than via the ticket
 * Delete button or the cleanup scan), remove the matching ticket row so it
 * stops counting against the per-category open-ticket limit.
 */
export default class ChannelDeleteEvent extends Event {
  constructor() {
    super("ChannelDelete", "channelDelete", false);
  }

  async execute(channel: DMChannel | GuildChannel) {
    if (!("guild" in channel)) return;
    try {
      const ticket = await appDb.getTicketByChannelId(channel.id);
      if (!ticket) return;
      await appDb.deleteTicketRow(ticket.id);
      logger.info(
        `Ticket: pruned row #${ticket.id} after its channel (${channel.id}) was deleted`,
      );
    } catch (e) {
      logger.error("Ticket: failed to prune row on channel delete:", e);
    }
  }
}
