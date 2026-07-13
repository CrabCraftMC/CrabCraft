import {
  type TextChannel,
  type Message,
  type APIEmbed,
  ComponentType,
  ButtonStyle,
  AttachmentBuilder,
} from "discord.js";
import logger from "./logger.js";

// ── Public API ────────────────────────────────────────────────────────

/**
 * Generate an HTML transcript of a channel and send it to the log channel.
 * Failures are logged but never thrown — this must not block channel deletion.
 */
export async function saveTranscriptToLog(
  channel: TextChannel,
  logChannel: TextChannel,
  _description: string,
): Promise<void> {
  try {
    const html = await generateTranscript(channel);
    const attachment = new AttachmentBuilder(Buffer.from(html, "utf-8"), {
      name: `transcript-${channel.name}.html`,
    });

    await logChannel.send({
      files: [attachment],
    });
  } catch (error) {
    logger.error("Failed to generate/send transcript:", error);
  }
}

// ── Transcript generation ─────────────────────────────────────────────

export async function generateTranscript(channel: TextChannel, maxMessages?: number): Promise<string> {
  const messages = await fetchAllMessages(channel, maxMessages);
  const rendered = messages.map((msg) => renderMessage(msg)).join("\n");
  return wrapInHtml(channel.name, rendered);
}

async function fetchAllMessages(channel: TextChannel, maxMessages?: number): Promise<Message[]> {
  const all: Message[] = [];
  let lastId: string | undefined;
  const cap = maxMessages ?? Infinity;

  while (all.length < cap) {
    const batch = await channel.messages.fetch({
      limit: Math.min(100, cap - all.length),
      ...(lastId ? { before: lastId } : {}),
    });
    if (batch.size === 0) break;
    all.push(...batch.values());
    lastId = batch.lastKey();
    if (batch.size < 100) break;
  }

  return all.reverse(); // chronological order
}

// ── Message rendering ─────────────────────────────────────────────────

function renderMessage(msg: Message): string {
  const author = msg.author;
  const avatar = author.displayAvatarURL({ size: 64 });
  const timestamp = msg.createdAt.toISOString();
  const isBot = author.bot ? ' bot="true"' : "";

  // Author header (always visible, independent of Skyra)
  const displayName = author.displayName ?? author.username;
  const time = msg.createdAt.toLocaleString("en-GB", { dateStyle: "short", timeStyle: "short" });
  const botBadge = author.bot ? ' <span class="msg-bot-badge">BOT</span>' : "";
  let inner = `<div class="msg-header"><img class="msg-avatar" src="${esc(avatar)}" alt="" /><span class="msg-author">${esc(displayName)}</span>${botBadge}<span class="msg-time">${esc(time)}</span></div>`;

  // Regular text content
  if (msg.content) {
    inner += `<div class="msg-content">${renderMarkdown(msg.content)}</div>`;
  }

  // V2 components
  if (msg.components.length > 0) {
    inner += renderComponents(msg.components);
  }

  // Classic embeds
  for (const embed of msg.embeds) {
    inner += renderEmbed(embed.data as APIEmbed);
  }

  // Attachments
  for (const [, att] of msg.attachments) {
    if (att.contentType?.startsWith("image/")) {
      inner += `<div class="msg-attachment"><img src="${esc(att.url)}" alt="${esc(att.name ?? "image")}" style="max-width:400px;border-radius:8px" /></div>`;
    } else {
      inner += `<div class="v2-file"><a href="${esc(att.url)}" target="_blank">${esc(att.name ?? "file")}</a> (${formatBytes(att.size)})</div>`;
    }
  }

  // Skip header-only messages (no actual content)
  const hasContent = msg.content || msg.components.length > 0 || msg.embeds.length > 0 || msg.attachments.size > 0;
  if (!hasContent) return "";

  return `<div class="msg">${inner}</div>`;
}

// ── V2 Component rendering ───────────────────────────────────────────
// Discord.js wraps API components in objects with getters.
// Top-level components: `.components` has child wrapper objects.
// Child wrapper objects: `.data` has raw API data with content/properties.

function renderComponents(components: Message["components"]): string {
  return components.map((c) => renderComponent(c)).join("");
}

function renderComponent(comp: any): string {
  const type = comp.type ?? comp.data?.type;
  switch (type) {
    case ComponentType.Container:
      return renderContainer(comp);
    case ComponentType.TextDisplay:
      return renderTextDisplay(comp);
    case ComponentType.Section:
      return renderSection(comp);
    case ComponentType.Thumbnail:
      return renderThumbnail(comp);
    case ComponentType.Separator:
      return renderSeparator(comp);
    case ComponentType.MediaGallery:
      return renderMediaGallery(comp);
    case ComponentType.ActionRow:
      return renderActionRow(comp);
    case ComponentType.Button:
      return renderButton(comp);
    default:
      return "";
  }
}

function renderContainer(comp: any): string {
  const color = comp.accentColor
    ? `border-left-color: #${comp.accentColor.toString(16).padStart(6, "0")}`
    : "";
  const children = (comp.components ?? []).map(renderComponent).join("");
  return `<div class="v2-container" style="${color}">${children}</div>`;
}

function renderTextDisplay(comp: any): string {
  const content = comp.content ?? comp.data?.content ?? "";
  return `<div class="v2-text">${renderMarkdown(content)}</div>`;
}

function renderSection(comp: any): string {
  const textParts = (comp.components ?? []).map(renderComponent).join("");
  const accessory = comp.accessory ? renderComponent(comp.accessory) : "";
  return `<div class="v2-section"><div class="v2-section-text">${textParts}</div>${accessory ? `<div class="v2-section-accessory">${accessory}</div>` : ""}</div>`;
}

function renderThumbnail(comp: any): string {
  const url = comp.media?.url ?? comp.data?.media?.url ?? "";
  const alt = comp.description ?? comp.data?.description ?? "";
  return `<img class="v2-thumbnail" src="${esc(url)}" alt="${esc(alt)}" />`;
}

function renderSeparator(comp: any): string {
  const divider = (comp.divider ?? comp.data?.divider) !== false;
  const large = (comp.spacing ?? comp.data?.spacing) === 2;
  if (!divider) return `<div class="v2-spacer${large ? " v2-spacer-lg" : ""}"></div>`;
  return `<hr class="v2-separator${large ? " v2-separator-lg" : ""}" />`;
}

function renderMediaGallery(comp: any): string {
  const items = (comp.items ?? comp.data?.items ?? [])
    .map((item: any) => {
      const url = item.media?.url ?? "";
      const alt = item.description ?? "";
      return `<img class="v2-gallery-img" src="${esc(url)}" alt="${esc(alt)}" />`;
    })
    .join("");
  return `<div class="v2-gallery">${items}</div>`;
}

function renderActionRow(comp: any): string {
  const children = (comp.components ?? []).map(renderComponent).join("");
  return `<div class="v2-action-row">${children}</div>`;
}

function renderButton(comp: any): string {
  const data = comp.data ?? comp;
  const style = data.style as number;
  const styleClass = {
    [ButtonStyle.Primary]: "v2-btn-primary",
    [ButtonStyle.Secondary]: "v2-btn-secondary",
    [ButtonStyle.Success]: "v2-btn-success",
    [ButtonStyle.Danger]: "v2-btn-danger",
    [ButtonStyle.Link]: "v2-btn-link",
    [ButtonStyle.Premium]: "v2-btn-secondary",
  }[style] ?? "v2-btn-secondary";

  const disabled = data.disabled ? " v2-btn-disabled" : "";
  const label = esc(data.label ?? "");
  let emoji = "";
  if (data.emoji) {
    if (data.emoji.id) {
      emoji = `<img class="v2-btn-emoji" src="https://cdn.discordapp.com/emojis/${data.emoji.id}.webp?size=20" alt="${esc(data.emoji.name ?? "")}" /> `;
    } else if (data.emoji.name) {
      emoji = `${data.emoji.name} `;
    }
  }

  if (style === ButtonStyle.Link && data.url) {
    return `<a class="v2-button ${styleClass}${disabled}" href="${esc(data.url)}" target="_blank">${emoji}${label} ↗</a>`;
  }

  return `<span class="v2-button ${styleClass}${disabled}">${emoji}${label}</span>`;
}

// ── Classic embed rendering ──────────────────────────────────────────

function renderEmbed(embed: APIEmbed): string {
  const color = embed.color
    ? `border-left-color: #${embed.color.toString(16).padStart(6, "0")}`
    : "";

  let inner = "";

  if (embed.author?.name) {
    inner += `<div class="embed-author">${esc(embed.author.name)}</div>`;
  }
  if (embed.title) {
    const titleHtml = embed.url
      ? `<a href="${esc(embed.url)}" target="_blank">${esc(embed.title)}</a>`
      : esc(embed.title);
    inner += `<div class="embed-title">${titleHtml}</div>`;
  }
  if (embed.description) {
    inner += `<div class="embed-desc">${renderMarkdown(embed.description)}</div>`;
  }
  if (embed.fields?.length) {
    inner += `<div class="embed-fields">${embed.fields.map((f) => `<div class="embed-field${f.inline ? " embed-field-inline" : ""}"><div class="embed-field-name">${esc(f.name)}</div><div class="embed-field-value">${renderMarkdown(f.value)}</div></div>`).join("")}</div>`;
  }
  if (embed.image?.url) {
    inner += `<img src="${esc(embed.image.url)}" style="max-width:400px;border-radius:4px;margin-top:8px" />`;
  }
  if (embed.thumbnail?.url) {
    inner = `<div style="display:flex;justify-content:space-between"><div>${inner}</div><img src="${esc(embed.thumbnail.url)}" style="max-width:80px;max-height:80px;border-radius:4px;margin-left:16px" /></div>`;
  }
  if (embed.footer?.text) {
    inner += `<div class="embed-footer">${esc(embed.footer.text)}${embed.timestamp ? ` • ${new Date(embed.timestamp).toLocaleString("en-GB", { dateStyle: "short", timeStyle: "short" })}` : ""}</div>`;
  }

  return `<div class="embed" style="${color}">${inner}</div>`;
}

// ── Discord markdown → HTML ──────────────────────────────────────────

function renderMarkdown(text: string): string {
  let html = esc(text);

  // Code blocks (must be first to avoid inner formatting)
  html = html.replace(
    /```(\w*)\n?([\s\S]*?)```/g,
    (_, lang, code) => `<pre><code class="lang-${lang}">${code}</code></pre>`,
  );

  // Inline code
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");

  // Headers (must be before bold)
  html = html.replace(/^### (.+)$/gm, "<h3>$1</h3>");
  html = html.replace(/^## (.+)$/gm, "<h2>$1</h2>");
  html = html.replace(/^# (.+)$/gm, "<h1>$1</h1>");
  html = html.replace(/^-# (.+)$/gm, '<small class="subtext">$1</small>');

  // Blockquotes
  html = html.replace(/^&gt; (.+)$/gm, "<blockquote>$1</blockquote>");

  // Bold + italic combined
  html = html.replace(/\*\*\*(.+?)\*\*\*/g, "<strong><em>$1</em></strong>");
  // Bold
  html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  // Italic
  html = html.replace(/(?<!\*)\*([^*]+)\*(?!\*)/g, "<em>$1</em>");
  // Underline
  html = html.replace(/__(.+?)__/g, "<u>$1</u>");
  // Strikethrough
  html = html.replace(/~~(.+?)~~/g, "<del>$1</del>");

  // Links [text](url)
  html = html.replace(
    /\[([^\]]+)\]\((&lt;)?([^)>]+)(&gt;)?\)/g,
    '<a href="$3" target="_blank">$1</a>',
  );

  // Masked URLs <url>
  html = html.replace(
    /&lt;(https?:\/\/[^>]+)&gt;/g,
    '<a href="$1" target="_blank">$1</a>',
  );

  // User mentions <@userId> or <@!userId>
  html = html.replace(
    /&lt;@!?(\d+)&gt;/g,
    '<span class="mention">@user</span>',
  );

  // Channel mentions <#channelId>
  html = html.replace(
    /&lt;#(\d+)&gt;/g,
    '<span class="mention">#channel</span>',
  );

  // Role mentions <@&roleId>
  html = html.replace(
    /&lt;@&amp;(\d+)&gt;/g,
    '<span class="mention">@role</span>',
  );

  // Custom emoji <:name:id> and <a:name:id>
  html = html.replace(
    /&lt;(a)?:(\w+):(\d+)&gt;/g,
    (_, animated, name, id) => {
      const ext = animated ? "gif" : "webp";
      return `<img class="emoji" src="https://cdn.discordapp.com/emojis/${id}.${ext}?size=20" alt=":${name}:" title=":${name}:" />`;
    },
  );

  // Unicode emoji (leave as-is, Skyra handles them)

  // Newlines
  html = html.replace(/\n/g, "<br>");

  return html;
}

// ── Utilities ─────────────────────────────────────────────────────────

function esc(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ── HTML template ─────────────────────────────────────────────────────

function wrapInHtml(channelName: string, body: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Transcript: #${esc(channelName)}</title>
<style>
  /* ── Base ─────────────────────────────────────────── */
  :root {
    color-scheme: dark;
  }
  body {
    margin: 0;
    padding: 0;
    background: #313338;
    color: #dbdee1;
    font-family: "gg sans", "Noto Sans", "Helvetica Neue", Helvetica, Arial, sans-serif;
    font-size: 16px;
    line-height: 1.375;
  }
  .transcript-header {
    padding: 16px 24px;
    background: #2b2d31;
    border-bottom: 1px solid #1e1f22;
    font-size: 14px;
    color: #b5bac1;
  }
  .transcript-header strong {
    color: #f2f3f5;
    font-size: 16px;
  }

  /* ── Message ─────────────────────────────────────── */
  .msg {
    padding: 4px 48px 4px 72px;
    position: relative;
    margin-top: 16px;
  }
  .msg:hover {
    background: #2e3035;
  }
  .msg-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 2px;
  }
  .msg-avatar {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    position: absolute;
    left: 16px;
    top: 4px;
  }
  .msg-author {
    font-weight: 600;
    color: #f2f3f5;
    font-size: 16px;
  }
  .msg-time {
    font-size: 12px;
    color: #949ba4;
  }
  .msg-bot-badge {
    background: #5865f2;
    color: #fff;
    font-size: 10px;
    font-weight: 600;
    padding: 1px 4px;
    border-radius: 3px;
    vertical-align: middle;
  }
  .msg-content {
    color: #dbdee1;
  }
  .msg-content a {
    color: #00a8fc;
    text-decoration: none;
  }
  .msg-content a:hover {
    text-decoration: underline;
  }
  .msg-attachment {
    margin: 4px 0;
  }

  /* ── Markdown overrides inside V2 ────────────────── */
  .v2-text h1, .v2-text h2, .v2-text h3 {
    margin: 4px 0;
    color: #f2f3f5;
    line-height: 1.3;
  }
  .v2-text h1 { font-size: 1.5em; }
  .v2-text h2 { font-size: 1.25em; }
  .v2-text h3 { font-size: 1.1em; }
  .v2-text small.subtext {
    display: block;
    font-size: 0.75em;
    color: #949ba4;
  }
  .v2-text a {
    color: #00a8fc;
    text-decoration: none;
  }
  .v2-text a:hover {
    text-decoration: underline;
  }
  .v2-text code {
    background: #1e1f22;
    padding: 0.15em 0.35em;
    border-radius: 4px;
    font-size: 0.875em;
    font-family: "Consolas", "Courier New", monospace;
  }
  .v2-text pre {
    background: #1e1f22;
    padding: 12px;
    border-radius: 8px;
    overflow-x: auto;
    margin: 4px 0;
  }
  .v2-text pre code {
    background: none;
    padding: 0;
  }
  .v2-text blockquote {
    border-left: 3px solid #4e5058;
    margin: 4px 0;
    padding-left: 12px;
    color: #b5bac1;
  }
  .v2-text strong { color: #f2f3f5; }

  /* ── V2 Container ────────────────────────────────── */
  .v2-container {
    background: #2b2d31;
    border-left: 3px solid transparent;
    border-radius: 8px;
    padding: 12px 16px;
    margin: 4px 0;
    max-width: 520px;
  }

  /* ── V2 Text Display ─────────────────────────────── */
  .v2-text {
    margin: 4px 0;
  }
  .v2-text .emoji {
    width: 20px;
    height: 20px;
    vertical-align: -4px;
    object-fit: contain;
  }

  /* ── V2 Section ──────────────────────────────────── */
  .v2-section {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin: 4px 0;
  }
  .v2-section-text {
    flex: 1;
    min-width: 0;
  }
  .v2-section-accessory {
    flex-shrink: 0;
  }

  /* ── V2 Thumbnail ────────────────────────────────── */
  .v2-thumbnail {
    width: 80px;
    height: 80px;
    border-radius: 8px;
    object-fit: cover;
  }

  /* ── V2 Separator ────────────────────────────────── */
  .v2-separator {
    border: none;
    border-top: 1px solid #3f4147;
    margin: 8px 0;
  }
  .v2-separator-lg { margin: 16px 0; }
  .v2-spacer { height: 8px; }
  .v2-spacer-lg { height: 16px; }

  /* ── V2 Media Gallery ────────────────────────────── */
  .v2-gallery {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin: 4px 0;
  }
  .v2-gallery-img {
    max-width: 300px;
    max-height: 300px;
    border-radius: 8px;
    object-fit: cover;
  }

  /* ── V2 Buttons ──────────────────────────────────── */
  .v2-action-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin: 8px 0;
  }
  .v2-button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 2px 16px;
    height: 32px;
    border-radius: 4px;
    font-size: 14px;
    font-weight: 500;
    cursor: default;
    text-decoration: none;
    color: #fff;
    line-height: 32px;
    box-sizing: border-box;
  }
  .v2-btn-primary { background: #5865f2; }
  .v2-btn-secondary { background: #4e5058; }
  .v2-btn-success { background: #248046; }
  .v2-btn-danger { background: #da373c; }
  .v2-btn-link { background: #4e5058; cursor: pointer; }
  .v2-btn-disabled { opacity: 0.5; }
  .v2-btn-emoji {
    width: 20px;
    height: 20px;
    object-fit: contain;
    vertical-align: middle;
  }

  /* ── File attachment ─────────────────────────────── */
  .v2-file {
    background: #2b2d31;
    padding: 8px 12px;
    border-radius: 8px;
    border: 1px solid #3f4147;
    margin: 4px 0;
    font-size: 14px;
  }
  .v2-file a {
    color: #00a8fc;
    text-decoration: none;
  }

  /* ── Emoji in regular content ────────────────────── */
  .emoji {
    width: 20px;
    height: 20px;
    vertical-align: -4px;
    object-fit: contain;
  }

  /* ── Mentions ────────────────────────────────────── */
  .mention {
    background: rgba(88, 101, 242, 0.3);
    color: #c9cdfb;
    padding: 0 2px;
    border-radius: 3px;
    font-weight: 500;
  }

  /* ── Classic Embeds ──────────────────────────────── */
  .embed {
    background: #2b2d31;
    border-left: 3px solid #4e5058;
    border-radius: 4px;
    padding: 8px 16px 8px 12px;
    margin: 4px 0;
    max-width: 520px;
  }
  .embed-author {
    font-size: 12px;
    font-weight: 600;
    color: #f2f3f5;
    margin-bottom: 4px;
  }
  .embed-title {
    font-weight: 600;
    color: #00a8fc;
    margin-bottom: 4px;
  }
  .embed-title a { color: #00a8fc; text-decoration: none; }
  .embed-title a:hover { text-decoration: underline; }
  .embed-desc {
    font-size: 14px;
    color: #dbdee1;
    margin-bottom: 8px;
  }
  .embed-fields {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .embed-field { min-width: 100%; }
  .embed-field-inline { min-width: 0; flex: 1; }
  .embed-field-name {
    font-size: 14px;
    font-weight: 600;
    color: #f2f3f5;
    margin-bottom: 2px;
  }
  .embed-field-value {
    font-size: 14px;
    color: #dbdee1;
  }
  .embed-footer {
    font-size: 12px;
    color: #949ba4;
    margin-top: 8px;
  }
</style>
</head>
<body>
<div class="transcript-header">
  <strong>#${esc(channelName)}</strong><br>
  Transcript generated on ${new Date().toLocaleString("en-GB", { dateStyle: "long", timeStyle: "short" })}
</div>
<div class="messages">
${body}
</div>
</body>
</html>`;
}
