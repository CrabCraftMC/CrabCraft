import type { Client, TextChannel } from "discord.js";
import { MessageFlags, ActionRowBuilder, ButtonBuilder, ButtonStyle } from "discord.js";
import { primaryContainer } from "./embeds.js";
import config from "./config.js";
import logger from "./logger.js";
import { WIKI_POLL_MS } from "./constants.js";

const WIKI_API = "https://wiki.crabcraft.net/api.php";
const WIKI_BASE = "https://wiki.crabcraft.net";

let lastRcid = 0;

interface WikiChange {
  type: string;
  title: string;
  user: string;
  comment: string;
  timestamp: string;
  rcid: number;
  newlen: number;
  oldlen: number;
  logtype?: string;
  logaction?: string;
  logparams?: { target_title?: string };
}

/** Strip Discord formatting and mentions from user-provided content. */
function sanitize(text: string): string {
  return text
    .replace(/@everyone/gi, "")
    .replace(/@here/gi, "")
    .replace(/<@[!&]?\d+>/g, "")
    .replace(/\*\*/g, "")
    .replace(/__/g, "")
    .replace(/~~/g, "")
    .replace(/\|\|/g, "")
    .replace(/`/g, "")
    .trim();
}

/** Convert a page title to a wiki URL. */
function pageUrl(title: string): string {
  return `<${WIKI_BASE}/${encodeURIComponent(title.replace(/ /g, "_"))}>`;
}

/** Format a masked hyperlink for a page. */
function pageLink(title: string): string {
  return `[${sanitize(title)}](${pageUrl(title)})`;
}

/** Format the size diff. */
function sizeDiff(change: WikiChange): string {
  const diff = change.newlen - change.oldlen;
  if (diff > 0) return `(+${diff})`;
  if (diff < 0) return `(${diff})`;
  return "(0)";
}

/** Format a single recent change into a Discord-friendly line. */
function formatChange(change: WikiChange): string {
  const user = sanitize(change.user);
  const comment = change.comment ? ` (${sanitize(change.comment).slice(0, 100)})` : "";

  // Log events (uploads, account creation, moves)
  if (change.type === "log") {
    if (change.logtype === "upload") {
      return `🖼️ **${user}** uploaded ${pageLink(change.title)}${comment}`;
    }
    if (change.logtype === "newusers") {
      return `🗿 Account **${user}** was created`;
    }
    if (change.logtype === "move" && change.logparams?.target_title) {
      return `📨 **${user}** moved ⤷ ${pageLink(change.title)} to ${pageLink(change.logparams.target_title)}`;
    }
    return `📋 **${user}** ${change.logaction ?? "updated"} ${pageLink(change.title)}${comment}`;
  }

  // New page
  if (change.type === "new") {
    return `🆕 **${user}** created ${pageLink(change.title)}${comment} ${sizeDiff(change)}`;
  }

  // Edit
  return `📝 **${user}** edited ${pageLink(change.title)}${comment} ${sizeDiff(change)}`;
}

/** Fetch recent changes from the MediaWiki API. */
async function fetchRecentChanges(): Promise<WikiChange[]> {
  const params = new URLSearchParams({
    action: "query",
    list: "recentchanges",
    rcprop: "title|ids|sizes|flags|user|timestamp|comment|loginfo",
    rclimit: "50",
    rcshow: "!bot",
    format: "json",
  });

  const res = await fetch(`${WIKI_API}?${params}`);
  if (!res.ok) throw new Error(`Wiki API returned ${res.status}`);

  const data = await res.json();
  return data.query?.recentchanges ?? [];
}

/** Poll for new changes and post to Discord. */
async function pollWikiChanges(client: Client): Promise<void> {
  try {
    const changes = await fetchRecentChanges();
    if (changes.length === 0) return;

    // Filter to only new changes
    const newChanges = changes
      .filter((c) => c.rcid > lastRcid)
      .sort((a, b) => a.rcid - b.rcid);

    if (newChanges.length === 0) return;

    // Update tracker
    lastRcid = Math.max(...newChanges.map((c) => c.rcid));

    // Format message
    const lines = newChanges.map(formatChange);
    const content = lines.join("\n");

    // Send to channel
    const channel = await client.channels
      .fetch(config.WIKI_CHANNEL_ID)
      .catch(() => null) as TextChannel | null;
    if (!channel) return;

    const wikiButton = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setLabel("Open Wiki")
        .setStyle(ButtonStyle.Link)
        .setURL("https://wiki.crabcraft.net/Special:RecentChanges"),
    );

    const message = await channel.send({
      components: [primaryContainer(`### Recent Changes\n${content}`), wikiButton],
      flags: MessageFlags.IsComponentsV2,
    });

    // Crosspost (announcement channel)
    await message.crosspost().catch(() => null);
  } catch (error) {
    logger.error("Error polling wiki changes:", error);
  }
}

/** Initialize the wiki poller. Sets the baseline rcid on first run, then polls every 5s. */
export async function initWikiPoller(client: Client): Promise<void> {
  if (!config.WIKI_CHANNEL_ID) return;

  try {
    // Set baseline - don't post existing changes
    const changes = await fetchRecentChanges();
    if (changes.length > 0) {
      lastRcid = Math.max(...changes.map((c) => c.rcid));
    }
    logger.info(`Wiki poller initialized (baseline rcid: ${lastRcid})`);
  } catch (error) {
    logger.error("Failed to initialize wiki poller:", error);
  }

  setInterval(() => pollWikiChanges(client), WIKI_POLL_MS);
}
