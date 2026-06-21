import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  ModalBuilder,
  TextInputBuilder,
  TextInputStyle,
  resolveColor,
} from "discord.js";
import type { ShopDeedApplication } from "./appDb.js";

export type ShopDeedFeedbackDecision = "changes_requested" | "rejected";

export interface ShopDeedField {
  id: string;
  label: string;
  display: string;
  style: TextInputStyle;
  required: boolean;
  placeholder?: string;
  maxLength?: number;
}

export const SHOP_DEED_FIELDS: ShopDeedField[] = [
  {
    id: "shop_name",
    label: "Shop name",
    display: "Shop Name",
    style: TextInputStyle.Short,
    required: true,
    placeholder: "e.g. The Honey Hut",
    maxLength: 80,
  },
  {
    id: "goods_services",
    label: "What will the shop sell?",
    display: "Goods / Services",
    style: TextInputStyle.Paragraph,
    required: true,
    placeholder: "List the main items or services you plan to offer.",
    maxLength: 600,
  },
  {
    id: "shop_description",
    label: "Describe the shop build",
    display: "Shop Description",
    style: TextInputStyle.Paragraph,
    required: true,
    placeholder: "Briefly describe the shop theme, size, and build plan.",
    maxLength: 1000,
  },
  {
    id: "location",
    label: "Planned location",
    display: "Planned Location",
    style: TextInputStyle.Short,
    required: false,
    placeholder: "Optional: district, plot, or coordinates",
    maxLength: 120,
  },
];

export function buildShopDeedPanel(): ContainerBuilder {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Green"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        [
          "## Shop Deed Requests",
          "Submit a short shop request before building on the SMP. Your request will open in a public thread for staff review.",
        ].join("\n"),
      ),
    );
}

export function buildShopDeedPanelButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId("shop_deed_open")
      .setLabel("Request Shop Deed")
      .setStyle(ButtonStyle.Primary),
  );
}

export function buildShopDeedModal(): ModalBuilder {
  const modal = new ModalBuilder()
    .setCustomId("shop_deed_modal")
    .setTitle("Shop Deed Request");

  for (const field of SHOP_DEED_FIELDS) {
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

export function buildShopDeedDecisionModal(
  applicationId: number,
  decision: ShopDeedFeedbackDecision,
): ModalBuilder {
  const isRequestChanges = decision === "changes_requested";
  const modal = new ModalBuilder()
    .setCustomId(`shop_deed_decision:${decision}:${applicationId}`)
    .setTitle(
      isRequestChanges ? "Request Changes" : "Reject Shop Deed",
    );

  const reason = new TextInputBuilder()
    .setCustomId("reason")
    .setLabel("Reason")
    .setStyle(TextInputStyle.Paragraph)
    .setRequired(true)
    .setPlaceholder(
      isRequestChanges
        ? "What does the applicant need to update or provide?"
        : "What should the applicant change before reapplying?",
    )
    .setMaxLength(1000);

  modal.addComponents(
    new ActionRowBuilder<TextInputBuilder>().addComponents(reason),
  );
  return modal;
}

export function buildShopDeedThreadName(
  shopName: string,
  username: string,
  status = "pending",
): string {
  const safeShop = normalizeThreadNamePart(shopName).slice(0, 50) || "SHOP";
  const safeUser = normalizeThreadNamePart(username).slice(0, 22) || "USER";
  return `${statusThreadPrefix(status)} | ${safeShop} | ${safeUser}`.slice(
    0,
    100,
  );
}

function normalizeThreadNamePart(value: string): string {
  return (
    value
      .toUpperCase()
      .replace(/\s+/g, " ")
      .replace(/[|]+/g, "")
      .trim()
  );
}

function statusThreadPrefix(status: string): string {
  if (status === "accepted") return "✅ APPROVED";
  if (status === "rejected") return "❌ NOT APPROVED";
  if (status === "changes_requested") return "⏳ CHANGES REQUESTED";
  return "⏳ PENDING";
}

export function buildShopDeedHeader(
  application: ShopDeedApplication,
): ContainerBuilder {
  const status = formatStatus(application.status);
  const requestCard = [
    "## Shop Deed Request",
    `**Request:** \`#${String(application.id).padStart(4, "0")}\``,
    `**Applicant:** \`${safeText(application.applicant_discord_username, 48)}\``,
    `**Status:** ${status}`,
  ].join("\n");

  const details = [
    `**Shop Name**\n${safeText(application.shop_name, 300)}`,
    `**Goods / Services**\n${safeText(application.goods_services, 1000)}`,
    `**Shop Description**\n${safeText(application.shop_description, 1500)}`,
  ];
  if (application.location) {
    details.push(
      `**Planned Location**\n${safeText(application.location, 300)}`,
    );
  }

  const container = new ContainerBuilder().setAccentColor(
    resolveColor(statusColor(application.status)),
  );
  container.addTextDisplayComponents((td) => td.setContent(requestCard));
  container.addTextDisplayComponents((td) =>
    td.setContent(details.join("\n\n")),
  );
  return container;
}

export function buildShopDeedStaffButtons(
  applicationId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`shop_deed_accept:${applicationId}`)
      .setLabel("Accept")
      .setStyle(ButtonStyle.Success),
    new ButtonBuilder()
      .setCustomId(`shop_deed_changes:${applicationId}`)
      .setLabel("Request Changes")
      .setStyle(ButtonStyle.Secondary),
    new ButtonBuilder()
      .setCustomId(`shop_deed_reject:${applicationId}`)
      .setLabel("Reject")
      .setStyle(ButtonStyle.Danger),
  );
}

export function buildDisabledShopDeedStaffButtons(
  applicationId: number,
): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`shop_deed_accept:${applicationId}`)
      .setLabel("Accept")
      .setStyle(ButtonStyle.Success)
      .setDisabled(true),
    new ButtonBuilder()
      .setCustomId(`shop_deed_changes:${applicationId}`)
      .setLabel("Request Changes")
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(true),
    new ButtonBuilder()
      .setCustomId(`shop_deed_reject:${applicationId}`)
      .setLabel("Reject")
      .setStyle(ButtonStyle.Danger)
      .setDisabled(true),
  );
}

export function buildShopDeedDecisionNotice(
  application: ShopDeedApplication,
  decision: ShopDeedFeedbackDecision,
  moderatorLabel: string,
  reason: string,
): ContainerBuilder {
  const isRequestChanges = decision === "changes_requested";
  const title = isRequestChanges
    ? "Changes Requested"
    : "Shop Deed Request Rejected";
  const intro = isRequestChanges
    ? `Staff requested changes for **${safeText(application.shop_name, 120)}**.`
    : `The request for **${safeText(application.shop_name, 120)}** was rejected by ${safeText(moderatorLabel, 48)}.`;
  const reasonTitle = isRequestChanges ? "Requested Changes" : "Reason";
  const next = isRequestChanges
    ? "Reply in this thread with updates or extra information. Staff can review it again from here."
    : "You can adjust the plan and submit another request when ready.";

  return new ContainerBuilder()
    .setAccentColor(resolveColor(isRequestChanges ? "Yellow" : "Red"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        [
          `## ${title}`,
          intro,
          "",
          `**${reasonTitle}**`,
          safeText(reason, 1200),
          "",
          next,
        ].join("\n"),
      ),
    );
}

function formatStatus(status: string): string {
  if (status === "accepted") return "Accepted";
  if (status === "changes_requested") return "Changes Requested";
  if (status === "rejected") return "Rejected";
  return "Pending";
}

function statusColor(
  status: string,
): "Green" | "Yellow" | "Red" | "Blurple" {
  if (status === "accepted") return "Green";
  if (status === "changes_requested") return "Yellow";
  if (status === "rejected") return "Red";
  return "Blurple";
}

function safeText(value: string, maxLength: number): string {
  const cleaned = value
    .replace(/@/g, "@\u200b")
    .replace(/`/g, "'")
    .trim();
  return cleaned.length > maxLength
    ? `${cleaned.slice(0, Math.max(0, maxLength - 3))}...`
    : cleaned;
}
