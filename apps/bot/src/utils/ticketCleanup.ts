import { TextChannel, type Client } from "discord.js";

import config from "./config.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";
import { isUnknownChannelError } from "./discordErrors.js";
import { withTicketLifecycleLock } from "./ticketLifecycle.js";
import { saveTranscriptToLog } from "./transcript.js";

export async function cleanupExpiredTicket(
  client: Client,
  ticketId: number,
  now: number,
): Promise<void> {
  await withTicketLifecycleLock(ticketId, async () => {
    const ticket = await appDb.getTicketById(ticketId).catch((e) => {
      logger.error(`Ticket cleanup: failed to recheck #${ticketId}:`, e);
      return null;
    });
    if (
      !ticket ||
      ticket.status !== "closed" ||
      ticket.delete_after == null ||
      ticket.delete_after > now
    ) {
      return;
    }

    let channel;
    try {
      channel = await client.channels.fetch(ticket.channel_id, { force: true });
      if (!channel) {
        logger.warn(
          `Ticket cleanup: channel lookup returned no result for #${ticket.id}; retaining row`,
        );
        return;
      }
    } catch (e) {
      if (!isUnknownChannelError(e)) {
        logger.error(
          `Ticket cleanup: failed to fetch channel for #${ticket.id}:`,
          e,
        );
        return;
      }
      channel = null;
    }

    if (channel) {
      if (channel instanceof TextChannel) {
        const logChannel = await client.channels
          .fetch(config.TICKET_LOG_CHANNEL_ID)
          .catch(() => null);
        if (logChannel instanceof TextChannel) {
          await saveTranscriptToLog(
            channel,
            logChannel,
            `ticket #${ticket.id} expired`,
          ).catch(() => null);
        }
      }

      try {
        await channel.delete(`Ticket #${ticket.id} expired`);
      } catch (e) {
        if (!isUnknownChannelError(e)) {
          logger.error(
            `Ticket cleanup: failed to delete channel for #${ticket.id}:`,
            e,
          );
          return;
        }
      }
    }

    await appDb
      .deleteTicketRow(ticket.id)
      .catch((e) =>
        logger.error(
          `Ticket cleanup: failed to delete row for #${ticket.id}:`,
          e,
        ),
      );
  });
}
