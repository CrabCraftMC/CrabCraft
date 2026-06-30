import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  EmbedBuilder,
  FileUploadBuilder,
  LabelBuilder,
  ModalBuilder,
  TextInputBuilder,
  TextInputStyle,
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
  /** Optional file-upload field appended to the modal (e.g. evidence). */
  fileField?: TicketFileField;
  /** Heading shown at the top of the ticket thread once opened. */
  headerTitle: string;
  /** Max simultaneous open tickets a single user may have in this category. */
  maxOpen: number;
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

export interface TicketFileField {
  /** Modal customId for the file-upload component. */
  id: string;
  /** Label shown above the upload control in the modal. */
  label: string;
  /** Helper text shown under the label. */
  description?: string;
  required: boolean;
  /** Max number of files (Discord allows up to 10). */
  maxValues: number;
}

export const TICKET_CATEGORIES: Record<TicketCategory, CategoryMeta> = {
  general: {
    category: "general",
    label: "General Question",
    emoji: "<:dialogue:1521557009936158883>",
    prefix: "gq",
    buttonStyle: ButtonStyle.Secondary,
    accent: 0x7584d6,
    modalTitle: "General Question",
    headerTitle: "General Question",
    maxOpen: 3,
    // No intake questions — clicking the button opens a ticket straight away.
    fields: [],
  },
  grief: {
    category: "grief",
    label: "Report Griefing / Stealing",
    emoji: "<:chest:1521557150919299325>",
    prefix: "rg",
    buttonStyle: ButtonStyle.Secondary,
    accent: 0xc46f62,
    modalTitle: "Report Griefing / Stealing",
    headerTitle: "Griefing / Stealing Report",
    maxOpen: 3,
    fields: [
      {
        id: "offender",
        label: "Offender's Minecraft username",
        display: "Offender",
        style: TextInputStyle.Short,
        required: false,
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
    ],
    fileField: {
      id: "evidence",
      label: "Evidence (screenshots / clips)",
      description: "Optional — upload images or video clips of what happened.",
      required: false,
      maxValues: 10,
    },
  },
  appeal: {
    category: "appeal",
    label: "Punishment Appeal",
    emoji: "<:judge_gavel:1521556773641650358>",
    prefix: "pa",
    buttonStyle: ButtonStyle.Secondary,
    accent: 0xc5a45d,
    modalTitle: "Punishment Appeal",
    headerTitle: "Punishment Appeal",
    maxOpen: 1,
    // We already know the appellant's Minecraft account from our database, and
    // their punishment history is shown automatically below, so the only thing
    // we ask for is their case. (If they aren't linked, the modal additionally
    // asks for their Minecraft username — see buildIntakeModal.)
    fields: [
      {
        id: "appeal",
        label: "Why should your infraction be removed?",
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

/**
 * The "you've hit the open-ticket limit for this category" notice, listing the
 * user's existing ticket channels so they can jump straight to them.
 */
export function buildTicketLimitNotice(
  meta: CategoryMeta,
  openChannelIds: string[],
): string {
  const single = openChannelIds.length === 1;
  const links = openChannelIds.map((id) => `<#${id}>`).join("\n");
  return [
    `**You already have ${openChannelIds.length} open ${meta.label} ticket${single ? "" : "s"}.** Please use ${single ? "it" : "one of these"} before opening any new tickets:`,
    links,
  ].join("\n");
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

/** customId of the optional "your Minecraft username" field on the appeal modal. */
export const TICKET_MANUAL_USERNAME_FIELD = "mc_username";

/** The Minecraft-username field injected into the appeal modal for unlinked users. */
const MANUAL_USERNAME_FIELD: TicketField = {
  id: TICKET_MANUAL_USERNAME_FIELD,
  label: "Your Minecraft username",
  display: "Minecraft username",
  style: TextInputStyle.Short,
  required: true,
  placeholder: "We couldn't find a linked account — enter it here",
  maxLength: 32,
};

export interface IntakeModalOptions {
  /** Prepend a required Minecraft-username field (used for unlinked appeals). */
  includeMinecraftUsername?: boolean;
}

/** The intake modal shown when a user clicks a category button. */
export function buildIntakeModal(
  meta: CategoryMeta,
  opts: IntakeModalOptions = {},
): ModalBuilder {
  const modal = new ModalBuilder()
    .setCustomId(`ticket_modal:${meta.category}`)
    .setTitle(meta.modalTitle);

  const fields = opts.includeMinecraftUsername
    ? [MANUAL_USERNAME_FIELD, ...meta.fields]
    : meta.fields;

  for (const field of fields) {
    const input = new TextInputBuilder()
      .setCustomId(field.id)
      .setStyle(field.style)
      .setRequired(field.required);
    if (field.placeholder) input.setPlaceholder(field.placeholder);
    if (field.maxLength) input.setMaxLength(field.maxLength);
    modal.addLabelComponents(
      new LabelBuilder().setLabel(field.label).setTextInputComponent(input),
    );
  }

  if (meta.fileField) {
    const upload = new FileUploadBuilder()
      .setCustomId(meta.fileField.id)
      .setRequired(meta.fileField.required)
      .setMinValues(meta.fileField.required ? 1 : 0)
      .setMaxValues(meta.fileField.maxValues);
    const label = new LabelBuilder()
      .setLabel(meta.fileField.label)
      .setFileUploadComponent(upload);
    if (meta.fileField.description) label.setDescription(meta.fileField.description);
    modal.addLabelComponents(label);
  }

  return modal;
}

/**
 * The header container posted in the channel when a ticket opens. Leads with the
 * category (custom emoji + `〉` + title), the ticket id and opener, then each
 * intake question with its answer in a code block.
 */
export function buildTicketHeader(
  meta: CategoryMeta,
  ticketId: number,
  openerDiscordId: string,
  intake: Record<string, string>,
): ContainerBuilder {
  const lines = [
    `## ${meta.emoji}  〉${meta.headerTitle}`,
    `**Ticket ID:** \`#${String(ticketId).padStart(4, "0")}\``,
    `**Opened by:** <@${openerDiscordId}>`,
  ];

  // One question/answer block per submitted field, keeping modal order.
  for (const field of meta.fields) {
    const value = intake[field.id];
    if (!value) continue;
    lines.push("", `**${field.label}**`, codeBlock(value));
  }

  return new ContainerBuilder()
    .setAccentColor(meta.accent)
    .addTextDisplayComponents((td) => td.setContent(lines.join("\n")));
}

/** Wrap a value in a fenced code block, neutralising any closing fence. */
function codeBlock(value: string): string {
  return `\`\`\`\n${value.replace(/```/g, "ʼʼʼ")}\n\`\`\``;
}

/** A ready-to-send message payload: a standard embed plus optional pager row. */
export interface InfractionEmbedMessage {
  embeds: EmbedBuilder[];
  components: ActionRowBuilder<ButtonBuilder>[];
}

/**
 * The appeal punishment record, rendered as a standard Discord embed titled
 * "{username}'s Infractions". One punishment is shown at a time with embed
 * fields; Prev/Next buttons page through the record when there's more than one.
 * Returns null when there's nothing to render (non-appeal tickets).
 */
export function buildInfractionEmbedMessage(
  ticketId: number,
  info: TicketInfractionInfo | null | undefined,
  page = 0,
): InfractionEmbedMessage | null {
  if (!info) return null;

  const name = safeText(info.username, 32);
  const baseEmbed = () =>
    new EmbedBuilder().setTitle(`${name}'s Infractions`);

  if (info.error) {
    return {
      embeds: [
        baseEmbed()
          .setColor(0x95a5a6)
          .setDescription(
            `Could not load punishment history.\n${safeText(info.error, 240)}`,
          ),
      ],
      components: [],
    };
  }

  if (!info.infractions || info.infractions.length === 0) {
    return {
      embeds: [
        baseEmbed().setColor(0x57f287).setDescription("No punishments on record."),
      ],
      components: [],
    };
  }

  const lastPage = info.infractions.length - 1;
  const safePage = Math.max(0, Math.min(page, lastPage));
  const infraction = info.infractions[safePage];
  if (!infraction) return null;

  const date =
    infraction.created_at > 0 ? `<t:${infraction.created_at}:f>` : "Unknown";

  const embed = baseEmbed()
    .setColor(0xf77069)
    .addFields({
      name: `#${infraction.id} ${formatInfractionType(infraction.type)}`,
      value: [
        `**Status:** ${infractionStatus(infraction)}`,
        `**Moderator:** \`${safeText(infraction.staff ?? "Unknown", 200)}\``,
        `**Reason:** \`${safeText(infraction.reason ?? "No reason given", 900)}\``,
        `**Date:** ${date}`,
      ].join("\n"),
    })
    .setFooter({
      text: `Punishment ${safePage + 1} of ${info.infractions.length}`,
    });

  const components =
    info.infractions.length > 1
      ? [buildInfractionNavigation(ticketId, safePage, lastPage)]
      : [];

  return { embeds: [embed], components };
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
  // An expiry in the past means the punishment has lapsed, regardless of the
  // stored `active` flag (which can lag behind).
  if (infraction.expires_at != null && infraction.expires_at <= now) {
    return "Expired";
  }
  if (infraction.active === false) return "Inactive";
  return "Active";
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

/** Custom emoji shown on the closed-ticket notice. */
const TICKET_CLOSED_EMOJI = "<:hourglass:1521560454189809886>";

/** Container shown once a ticket is closed; references the delete countdown. */
export function buildClosedNotice(
  closedByMention: string,
  deleteAtEpochSeconds: number,
): ContainerBuilder {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("DarkButNotBlack"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        `### ${TICKET_CLOSED_EMOJI} 〉Ticket closed\nThis ticket was closed by ${closedByMention}. It will be automatically deleted <t:${deleteAtEpochSeconds}:R>.`,
      ),
    );
}

/** Reopen + Delete buttons shown alongside the closed notice. */
export function buildClosedTicketButtons(
  ticketId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`ticket_reopen:${ticketId}`)
      .setLabel("Reopen Ticket")
      .setStyle(ButtonStyle.Success),
    new ButtonBuilder()
      .setCustomId(`ticket_delete:${ticketId}`)
      .setLabel("Delete Ticket")
      .setStyle(ButtonStyle.Danger),
  );
}

/** Same Reopen + Delete row, both disabled — used to grey it after reopen. */
export function buildDisabledClosedTicketButtons(
  ticketId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`ticket_reopen:${ticketId}`)
      .setLabel("Reopen Ticket")
      .setStyle(ButtonStyle.Success)
      .setDisabled(true),
    new ButtonBuilder()
      .setCustomId(`ticket_delete:${ticketId}`)
      .setLabel("Delete Ticket")
      .setStyle(ButtonStyle.Danger)
      .setDisabled(true),
  );
}

/** The structured topic set on a ticket channel. */
export function buildTicketTopic(opts: {
  ticketId: number;
  openerName: string;
  meta: CategoryMeta;
  openedAtEpochSeconds: number;
}): string {
  return [
    `- **Ticket ID**: #${String(opts.ticketId).padStart(4, "0")}`,
    `- **Ticket opened**: ${opts.openerName}`,
    `- **Category**: ${opts.meta.label}`,
    `- **Opened At**: <t:${opts.openedAtEpochSeconds}:f>`,
  ].join("\n");
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
