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

// ── Log channel embeds ──────────────────────────────────────────────

/** Log embed for an accepted application (green accent + player skin). */
export function logAccept(
  userId: string,
  mcUsername: string,
  uuid: string,
  moderator?: string,
) {
  const byLine = moderator ? `\n**By:** ${moderator}` : "";
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Green"))
    .addSectionComponents(
      new SectionBuilder()
        .addTextDisplayComponents((td) =>
          td.setContent(
            `### <:PlayerJoined:1251574077186113606> Application Accepted\n**User:** <@${userId}>\n**Minecraft:** \`${mcUsername}\`${byLine}`,
          ),
        )
        .setThumbnailAccessory(
          new ThumbnailBuilder().setURL(
            `https://api.mineatar.io/body/full/${uuid}?scale=12`,
          ),
        ),
    );
}

/** Log embed for a denied application (red accent). */
export function logDeny(
  userId: string,
  mcUsername: string | undefined,
  reason: string,
  moderator?: string,
) {
  const mcLine = mcUsername ? `\n**Minecraft:** \`${mcUsername}\`` : "";
  const byLine = moderator ? `\n**Denied by:** ${moderator}` : "";
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Red"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        `### <:PlayerDeath:1251574756600316057> Application Denied\n**User:** <@${userId}>${mcLine}\n**Reason:** ${reason}${byLine}`,
      ),
    );
}

/** Log embed for an automatic rejection (red accent, no moderator). */
export function logAutoReject(userId: string, reason: string) {
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Red"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        `### <:PlayerDeath:1251574756600316057> Auto-Rejected\n**User:** <@${userId}>\n**Reason:** ${reason}`,
      ),
    );
}

/** Log embed for a member leaving the server. */
export function logMemberLeft(userTag: string, mcUsername: string) {
  return new ContainerBuilder()
    .addTextDisplayComponents((td) =>
      td.setContent(
        `### <:PlayerLeft:1251574076061913179> Member Left\n**User:** ${userTag}\n**Minecraft:** \`${mcUsername}\`\nRemoved from whitelist.`,
      ),
    );
}

/** Log embed for an admin wipe action (orange accent). */
export function logAdminWipe(
  target: string,
  mcUsername: string,
  moderator: string,
  extra?: string,
) {
  const extraLine = extra ? `\n${extra}` : "";
  return new ContainerBuilder()
    .setAccentColor(resolveColor("Orange"))
    .addTextDisplayComponents((td) =>
      td.setContent(
        `### <:PlayerLeft:1251574076061913179> Data Wiped\n**Target:** ${target}\n**Minecraft:** \`${mcUsername}\`\n**By:** ${moderator}${extraLine}`,
      ),
    );
}
