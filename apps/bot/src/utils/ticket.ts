import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  ModalBuilder,
  SectionBuilder,
  TextInputBuilder,
  TextInputStyle,
  ThumbnailBuilder,
  resolveColor,
} from "discord.js";
import mysql from "./database.js";
import logger from "./logger.js";
import * as appDb from "./appDb.js";
import type { TicketCategory } from "./appDb.js";
import type { PublicInfraction, TicketInfractionInfo } from "./infractions.js";

export const TICKET_INFRACTION_BUTTON_PREFIX = "ticket_infraction";

// ── Category metadata ──────────────────────────────────────────────

export interface CategoryMeta {
  category: TicketCategory;
  label: string;
  emoji: string;
  /** Short prefix used in thread channel names. */
  prefix: string;
  /** Discord button style on the trigger embed. */
  buttonStyle: ButtonStyle;
  /** Softer container accent colour. */
  accent: number;
  modalTitle: string;
  fields: TicketField[];
  /** Heading shown at the top of the ticket thread once opened. */
  headerTitle: string;
}

export interface TicketField {
  /** Modal customId for the input. */
  id: string;
  /** Label shown in the modal. */
  label: string;
  /** Field name shown in the ticket header in the thread. */
  display: string;
  style: TextInputStyle;
  required: boolean;
  placeholder?: string;
  maxLength?: number;
}

export const TICKET_CATEGORIES: Record<TicketCategory, CategoryMeta> = {
  general: {
    category: "general",
    label: "General Question",
    emoji: "❓",
    prefix: "gq",
    buttonStyle: ButtonStyle.Primary,
    accent: 0x7584d6,
    modalTitle: "General Question",
    headerTitle: "General Question",
    fields: [
      {
        id: "subject",
        label: "Subject",
        display: "Subject",
        style: TextInputStyle.Short,
        required: true,
        placeholder: "Short summary of your question",
        maxLength: 100,
      },
      {
        id: "description",
        label: "What can we help you with?",
        display: "Description",
        style: TextInputStyle.Paragraph,
        required: true,
        placeholder: "Provide as much detail as possible…",
        maxLength: 1500,
      },
    ],
  },
  grief: {
    category: "grief",
    label: "Report Griefing / Stealing",
    emoji: "⚠️",
    prefix: "rg",
    buttonStyle: ButtonStyle.Danger,
    accent: 0xc46f62,
    modalTitle: "Report Griefing / Stealing",
    headerTitle: "Griefing / Stealing Report",
    fields: [
      {
        id: "offender",
        label: "Offender's Minecraft username",
        display: "Offender",
        style: TextInputStyle.Short,
        required: true,
        placeholder: "Steve",
        maxLength: 32,
      },
      {
        id: "location",
        label: "Location / coordinates",
        display: "Location",
        style: TextInputStyle.Short,
        required: false,
        placeholder: "e.g. Overworld 1234 64 -512 (or a base name)",
        maxLength: 100,
      },
      {
        id: "when",
        label: "When did it happen?",
        display: "When",
        style: TextInputStyle.Short,
        required: false,
        placeholder: "e.g. Today around 8pm UTC",
        maxLength: 100,
      },
      {
        id: "description",
        label: "What happened?",
        display: "Description",
        style: TextInputStyle.Paragraph,
        required: true,
        placeholder: "Describe what was griefed/stolen and any context…",
        maxLength: 1500,
      },
      {
        id: "evidence",
        label: "Evidence (links to screenshots/clips)",
        display: "Evidence",
        style: TextInputStyle.Paragraph,
        required: false,
        placeholder: "Imgur, YouTube, etc. You can also upload in the ticket.",
        maxLength: 1000,
      },
    ],
  },
  appeal: {
    category: "appeal",
    label: "Punishment Appeal",
    emoji: "📜",
    prefix: "pa",
    buttonStyle: ButtonStyle.Secondary,
    accent: 0xc5a45d,
    modalTitle: "Punishment Appeal",
    headerTitle: "Punishment Appeal",
    // We already know the appellant's Minecraft account from our database, and
    // their punishment history is shown automatically below, so we only ask for
    // the timing and their case.
    fields: [
      {
        id: "ban_date",
        label: "When were you punished?",
        display: "When",
        style: TextInputStyle.Short,
        required: false,
        placeholder: "Approximate date/time",
        maxLength: 100,
      },
      {
        id: "appeal",
        label: "Why should we unban you?",
        display: "Appeal",
        style: TextInputStyle.Paragraph,
        required: true,
        placeholder: "Explain in detail. Be honest, staff will check the logs.",
        maxLength: 1500,
      },
    ],
  },
};

export function getCategoryMeta(category: string): CategoryMeta | null {
  return TICKET_CATEGORIES[category as TicketCategory] ?? null;
}

// ── Player info helper ────────────────────────────────────────────

export interface PlayerInfo {
  discordId: string;
  discordTag: string;
  minecraftUsername: string | null;
  minecraftUuid: string | null;
  isWhitelisted: boolean;
  skinUrl: string | null;
}

/** Pull minimal player context for a ticket header. Never throws. */
export async function getPlayerInfo(
  discordId: string,
  discordTag: string,
): Promise<PlayerInfo> {
  let minecraftUsername: string | null = null;
  let minecraftUuid: string | null = null;
  let isWhitelisted = false;

  try {
    const link = await appDb.getPlayerLink(discordId);
    if (link) {
      minecraftUsername = link.minecraft_username;
      minecraftUuid = link.minecraft_uuid;
    }
  } catch (e) {
    logger.error("Ticket: failed to load player row:", e);
  }

  try {
    const rows = await mysql.query(
      "SELECT uuid FROM discordsrv_accounts WHERE discord = ? LIMIT 1",
      [discordId],
    );
    if (rows.length > 0) {
      isWhitelisted = true;
      if (!minecraftUuid && rows[0].uuid) minecraftUuid = rows[0].uuid;
    }
  } catch (e) {
    logger.error("Ticket: failed to check whitelist status:", e);
  }

  return {
    discordId,
    discordTag,
    minecraftUsername,
    minecraftUuid,
    isWhitelisted,
    skinUrl: minecraftUuid
      ? `https://api.mineatar.io/body/full/${minecraftUuid}?scale=8`
      : null,
  };
}

// ── Builders ──────────────────────────────────────────────────────

/** The public-facing trigger embed posted via /admin send. */
export function buildTriggerEmbed(): ContainerBuilder {
  const lines = [
    "## <:Crab:1397355651822256299> Open a Support Ticket",
    "Need help? Pick the option that best describes your issue and a private thread will be opened with the staff team.",
    "",
    "**<:Crab:1397355651822256299> Categories**",
    `${TICKET_CATEGORIES.general.emoji} **General Question** — ask about the server, voice chat, or anything else.`,
    `${TICKET_CATEGORIES.grief.emoji} **Report Griefing / Stealing** — report a player who griefed or stole from you.`,
    `${TICKET_CATEGORIES.appeal.emoji} **Punishment Appeal** — appeal a ban, mute, or warning.`,
    "",
    "-# Tickets are private. Only you and staff can see them.",
  ];
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Blurple"))
    .addTextDisplayComponents((td) => td.setContent(lines.join("\n")));
}

/** Action row of category buttons for the trigger embed. */
export function buildTriggerButtons(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    ...Object.values(TICKET_CATEGORIES).map((meta) =>
      new ButtonBuilder()
        .setCustomId(`ticket_open:${meta.category}`)
        .setLabel(meta.label)
        .setEmoji(meta.emoji)
        .setStyle(meta.buttonStyle),
    ),
  );
}

/** The intake modal shown when a user clicks a category button. */
export function buildIntakeModal(meta: CategoryMeta): ModalBuilder {
  const modal = new ModalBuilder()
    .setCustomId(`ticket_modal:${meta.category}`)
    .setTitle(meta.modalTitle);

  for (const field of meta.fields) {
    const input = new TextInputBuilder()
      .setCustomId(field.id)
      .setLabel(field.label)
      .setStyle(field.style)
      .setRequired(field.required);
    if (field.placeholder) input.setPlaceholder(field.placeholder);
    if (field.maxLength) input.setMaxLength(field.maxLength);
    modal.addComponents(
      new ActionRowBuilder<TextInputBuilder>().addComponents(input),
    );
  }

  return modal;
}

/**
 * The header container posted in the thread when a ticket opens.
 * Shows player info + the intake answers so staff have context.
 */
export function buildTicketHeader(
  meta: CategoryMeta,
  player: PlayerInfo,
  intake: Record<string, string>,
): ContainerBuilder {
  // The opener is pinged in the message above this one and the channel is named
  // after them, so the header intentionally omits the ticket id and "opened by".
  const isAppeal = meta.category === "appeal";

  const cardLines = [`## ${meta.headerTitle}`];
  // Appeals carry the player in the infraction footer below, so keep it minimal.
  if (!isAppeal) {
    cardLines.push(
      player.minecraftUsername
        ? `**Minecraft:** \`${player.minecraftUsername}\``
        : "**Minecraft:** _not linked_",
      `**Whitelist:** ${player.isWhitelisted ? "Verified" : "Not whitelisted"}`,
    );
  }
  const playerCard = cardLines.join("\n");

  // Build the intake summary from the modal fields, keeping submission order.
  const intakeLines: string[] = [];
  for (const field of meta.fields) {
    const value = intake[field.id];
    if (!value) continue;
    intakeLines.push(`**${field.display}**\n${value}`);
  }

  const container = new ContainerBuilder().setAccentColor(meta.accent);

  if (player.skinUrl) {
    container.addSectionComponents(
      new SectionBuilder()
        .addTextDisplayComponents((td) => td.setContent(playerCard))
        .setThumbnailAccessory(
          new ThumbnailBuilder().setURL(player.skinUrl),
        ),
    );
  } else {
    container.addTextDisplayComponents((td) => td.setContent(playerCard));
  }

  if (intakeLines.length > 0) {
    container.addTextDisplayComponents((td) =>
      td.setContent(intakeLines.join("\n\n")),
    );
  }

  return container;
}

type TicketOpeningComponent =
  | ContainerBuilder
  | ActionRowBuilder<ButtonBuilder>;

/**
 * The appeal punishment record, rendered as a quiet, footer-style panel:
 * a colourless container of Discord subtext (`-#`) showing one punishment at a
 * time. Prev/Next buttons page through the record when there's more than one.
 */
export function buildTicketInfractionComponents(
  ticketId: number,
  info: TicketInfractionInfo | null | undefined,
  page = 0,
): TicketOpeningComponent[] {
  if (!info) return [];

  const name = safeText(info.username, 24);

  if (info.error) {
    return [
      footerContainer(
        `-# 👤 \`${name}\` · could not load punishment history (${safeText(info.error, 120)})`,
      ),
    ];
  }

  if (!info.infractions || info.infractions.length === 0) {
    return [footerContainer(`-# 👤 \`${name}\` · no punishments on record`)];
  }

  const lastPage = info.infractions.length - 1;
  const safePage = Math.max(0, Math.min(page, lastPage));
  const infraction = info.infractions[safePage];
  if (!infraction) return [];

  const components: TicketOpeningComponent[] = [
    footerContainer(
      formatInfractionFooter(name, infraction, safePage, info.infractions.length),
    ),
  ];

  if (info.infractions.length > 1) {
    components.push(buildInfractionNavigation(ticketId, safePage, lastPage));
  }

  return components;
}

/** A colourless container holding the footer-style infraction text. */
function footerContainer(content: string): ContainerBuilder {
  return new ContainerBuilder().addTextDisplayComponents((td) =>
    td.setContent(content),
  );
}

function formatInfractionFooter(
  name: string,
  infraction: PublicInfraction,
  page: number,
  total: number,
): string {
  const type = formatInfractionType(infraction.type);
  const status = infractionStatus(infraction);
  const when = infraction.created_at > 0
    ? `<t:${infraction.created_at}:d>`
    : "unknown date";
  const staff = safeText(infraction.staff ?? "Unknown", 48);
  const reason = safeText(infraction.reason ?? "No reason given", 160);

  const lines = [
    `-# 👤 \`${name}\` · punishment ${page + 1} of ${total}`,
    `-# **${type}** · ${status} · ${when} · by ${staff}`,
    `-# Reason: ${reason}`,
  ];
  if (infraction.removed) {
    lines.push(
      `-# Removed by ${safeText(infraction.removed_by ?? "Unknown", 48)}`,
    );
  }
  return lines.join("\n");
}

function buildInfractionNavigation(
  ticketId: number,
  page: number,
  lastPage: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`${TICKET_INFRACTION_BUTTON_PREFIX}:${ticketId}:${page - 1}`)
      .setLabel("Previous")
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(page === 0),
    new ButtonBuilder()
      .setCustomId(`${TICKET_INFRACTION_BUTTON_PREFIX}:${ticketId}:current`)
      .setLabel(`${page + 1} of ${lastPage + 1}`)
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(true),
    new ButtonBuilder()
      .setCustomId(`${TICKET_INFRACTION_BUTTON_PREFIX}:${ticketId}:${page + 1}`)
      .setLabel("Next")
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(page === lastPage),
  );
}

function formatInfractionType(type: PublicInfraction["type"]): string {
  switch (type) {
    case "ban":
      return "Ban";
    case "mute":
      return "Mute";
    case "warning":
      return "Warning";
    case "kick":
      return "Kick";
  }
}

function infractionStatus(infraction: PublicInfraction): string {
  const now = Math.floor(Date.now() / 1000);
  if (infraction.removed) return "Removed";
  if (infraction.active === true) {
    if (!infraction.expires_at) return "Active, permanent";
    return `Active, expires <t:${infraction.expires_at}:R>`;
  }
  if (infraction.expires_at && infraction.expires_at <= now) return "Expired";
  if (infraction.active === false) return "Inactive";
  if (!infraction.expires_at) return "Recorded";
  return `Expires <t:${infraction.expires_at}:R>`;
}

function safeText(value: string, maxLength: number): string {
  const cleaned = value
    .replace(/@/g, "@\u200b")
    .replace(/`/g, "'")
    .replace(/\s+/g, " ")
    .trim();
  return cleaned.length > maxLength
    ? `${cleaned.slice(0, Math.max(0, maxLength - 1))}…`
    : cleaned;
}

/** Action row for staff: just Close. */
export function buildStaffButtons(
  ticketId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`ticket_close:${ticketId}`)
      .setLabel("Close Ticket")
      .setStyle(ButtonStyle.Danger),
  );
}

/** Same Close button, disabled — used to grey the row after close. */
export function buildDisabledStaffButtons(
  ticketId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`ticket_close:${ticketId}`)
      .setLabel("Close Ticket")
      .setStyle(ButtonStyle.Danger)
      .setDisabled(true),
  );
}

/** Container shown once a ticket is closed; references the delete countdown. */
export function buildClosedNotice(
  closedByMention: string,
  deleteAtEpochSeconds: number,
): ContainerBuilder {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("DarkButNotBlack"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        `## Ticket Closed\nClosed by ${closedByMention}.\n-# This channel will be deleted <t:${deleteAtEpochSeconds}:R>. Mods can reopen below to restore opener access.`,
      ),
    );
}

/** Reopen button shown alongside the closed notice. */
export function buildReopenButton(
  ticketId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`ticket_reopen:${ticketId}`)
      .setLabel("Reopen Ticket")
      .setStyle(ButtonStyle.Success),
  );
}

/** Same Reopen button, disabled — used to grey the row after reopen. */
export function buildDisabledReopenButton(
  ticketId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`ticket_reopen:${ticketId}`)
      .setLabel("Reopen Ticket")
      .setStyle(ButtonStyle.Success)
      .setDisabled(true),
  );
}

/** Container shown when a ticket is reopened. */
export function buildReopenedNotice(reopenedByMention: string): ContainerBuilder {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Green"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        `## Ticket Reopened\nReopened by ${reopenedByMention}. The opener has been restored to the channel.`,
      ),
    );
}

/**
 * Build a channel name like `steve-grief`. Discord allows up to 100 chars
 * for channel names; usernames are sanitised to letters/numbers/dash/underscore.
 */
export function buildChannelName(
  username: string,
  meta: CategoryMeta,
): string {
  const safe = username
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80) || "user";
  return `${safe}-${meta.category}`;
}
