import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
} from "discord.js";
import { primaryContainer } from "./embeds.js";

export const SEASON_PLAY_BUTTON_ID = "season-play";

/**
 * The Season Seven access panel (posted via /admin send).
 * Placeholder copy — the final announcement text will be supplied later.
 */
export function buildSeasonAccessContainer() {
  return primaryContainer(
    "## Season Seven\nClick the button below to confirm you're playing Season Seven and get access. You must already be whitelisted — if anything goes wrong, we'll point you to a ticket.",
  );
}

export function buildSeasonAccessButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(SEASON_PLAY_BUTTON_ID)
      .setLabel("Play season seven")
      .setStyle(ButtonStyle.Primary),
  );
}

/**
 * "Open ticket" fallback shown when we can't locate the clicker's Minecraft
 * account. Reuses the existing general-ticket flow (no intake questions, the
 * ticket opens straight away).
 */
export function buildOpenTicketButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId("ticket_open:general")
      .setLabel("Open ticket")
      .setStyle(ButtonStyle.Secondary),
  );
}
