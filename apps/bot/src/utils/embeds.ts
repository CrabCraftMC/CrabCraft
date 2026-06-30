import {
  ContainerBuilder,
  SectionBuilder,
  ThumbnailBuilder,
  resolveColor,
} from "discord.js";

/** Primary container with markdown text. */
export function primaryContainer(text: string) {
  return new ContainerBuilder()
    .addTextDisplayComponents((td) => td.setContent(text));
}

/** Error container with red accent and markdown text. */
export function errorContainer(text: string) {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Red"))
    .addTextDisplayComponents((td) => td.setContent(text));
}

/** Success container with green accent and markdown text. */
export function successContainer(text: string) {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Green"))
    .addTextDisplayComponents((td) => td.setContent(text));
}

/** Container with a specific accent color. */
export function coloredContainer(text: string, color: "Green" | "Red") {
  return new ContainerBuilder()
    .setAccentColor(resolveColor(color))
    .addTextDisplayComponents((td) => td.setContent(text));
}

/** Primary container with a thumbnail accessory (e.g. player skin). */
export function primaryContainerWithThumbnail(text: string, imageUrl: string) {
  return new ContainerBuilder()
    .addSectionComponents(
      new SectionBuilder()
        .addTextDisplayComponents((td) => td.setContent(text))
        .setThumbnailAccessory(
          new ThumbnailBuilder().setURL(imageUrl),
        ),
    );
}

// ── Log channel messages ────────────────────────────────────────────

/** Log message for an accepted application. */
export function logAccept(
  userId: string,
  mcUsername: string,
  _uuid: string,
  moderator?: string,
) {
  const approvedBy = moderator ? ` by **${moderator}**` : "";
  return `<:PlayerJoined:1251574077186113606> **<@${userId}>**'s application was approved${approvedBy}. \`${mcUsername}\` was added to the whitelist.`;
}

/** Log message for a denied application. */
export function logDeny(
  userId: string,
  mcUsername: string | undefined,
  reason: string,
  moderator?: string,
) {
  const deniedBy = moderator ? ` by **${moderator}**` : "";
  const username = mcUsername ? ` \`${mcUsername}\` was not added to the whitelist.` : "";
  return `<:PlayerDeath:1251574756600316057> **<@${userId}>**'s application was denied${deniedBy}.${username} **Reason:** ${reason}`;
}

/** Log message for an automatic rejection. */
export function logAutoReject(userId: string, reason: string) {
  return `<:PlayerDeath:1251574756600316057> **<@${userId}>**'s application was automatically rejected. **Reason:** ${reason}`;
}

/** Log message for a member leaving the server. */
export function logMemberLeft(user: string, mcUsername: string) {
  return `<:PlayerLeft:1251574076061913179> **${user}** left the server. \`${mcUsername}\` was removed from the whitelist.`;
}

/** Log message for an admin wipe action. */
export function logAdminWipe(
  target: string,
  mcUsername: string,
  moderator: string,
  extra?: string,
) {
  const extraLine = extra ? ` ${extra}` : "";
  return `<:PlayerLeft:1251574076061913179> **${target}** was wiped by **${moderator}**. \`${mcUsername}\` was removed from the whitelist.${extraLine}`;
}
