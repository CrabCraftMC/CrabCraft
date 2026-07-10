import path from "path";
import { fileURLToPath } from "url";
import sharp from "sharp";

export interface OnlinePlayer {
  username: string;
  uuid: string;
  nickname: string | null;
  nickname_raw: string | null;
  ping: number | null;
  server: string | null;
  current_streak: number;
}

export interface OnlinePlayers {
  count: number;
  players: OnlinePlayer[];
}

interface ApiPlayer {
  username?: unknown;
  uuid?: unknown;
  nickname?: unknown;
  nickname_raw?: unknown;
  ping?: unknown;
  server?: unknown;
  current_streak?: unknown;
}

interface ApiPlayersResponse {
  count?: unknown;
  players?: unknown;
}

interface RawImage {
  data: Buffer;
  width: number;
  height: number;
}

interface TextSegment {
  text: string;
  color: string;
  bold: boolean;
  strikethrough: boolean;
  italic: boolean;
}

interface Glyph {
  texture: "ascii" | "nonlatin";
  sx: number;
  sy: number;
  width: number;
  height: number;
  advance: number;
}

interface FontContext {
  textures: {
    ascii: RawImage;
    nonlatin: RawImage;
  };
  glyphs: Map<number, Glyph>;
}

export interface PlayerListImageOptions {
  showAvatar?: boolean;
  showPing?: boolean;
  maxPlayers?: number;
  fetchAvatars?: boolean;
}

const FETCH_TIMEOUT_MS = 5000;
const AVATAR_FETCH_TIMEOUT_MS = 3000;

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSET_DIR = path.resolve(__dirname, "../../assets/player-list");
const FONT_TEXTURE_PATH = path.join(ASSET_DIR, "ascii.png");
const NONLATIN_FONT_TEXTURE_PATH = path.join(ASSET_DIR, "nonlatin_european.png");
const PING_TEXTURE_PATHS = {
  1: path.join(ASSET_DIR, "ping_1.png"),
  2: path.join(ASSET_DIR, "ping_2.png"),
  3: path.join(ASSET_DIR, "ping_3.png"),
  4: path.join(ASSET_DIR, "ping_4.png"),
  5: path.join(ASSET_DIR, "ping_5.png"),
  unknown: path.join(ASSET_DIR, "ping_unknown.png"),
} as const;

const SCALE = 2;
const TABLIST_SINGLE_COLUMN_LIMIT = 20;
const TABLIST_PLAYER_DISPLAY_LIMIT = 80;
const TABLIST_BACKGROUND = parseHex("#444444");
const TABLIST_PLAYER_BACKGROUND = parseHex("#6b6b6b");
const CHAT_COLOR_BACKGROUND_FACTOR = 0.19;
const TEXT_Y_OFFSET = 1;
const DECORATION_PADDING = 48;
const TITLE_MIN_PADDING = 128;
const HEADER_RULE_MIN_SPACES = 6;
const FOOTER_RULE_MIN_SPACES = 18;
const CRABCRAFT_TITLE = "<#fca800>ᴄʀᴀʙᴄʀᴀғᴛ";
const SEASON_LINE = "&7sᴇᴀsᴏɴ ᴠɪɪ";

const NONLATIN_GLYPHS = new Map<number, [number, number]>([
  [0x026a, [12, 9]],
  [0x0493, [8, 12]],
  [0x1d00, [7, 37]],
  [0x0299, [8, 37]],
  [0x1d04, [9, 37]],
  [0x1d07, [11, 37]],
  [0x0274, [3, 38]],
  [0x1d0f, [4, 38]],
  [0x0280, [7, 38]],
  [0x1d1b, [9, 38]],
  [0x1d20, [11, 38]],
]);

const LEGACY_COLORS: Record<string, string> = {
  "0": "#000000",
  "1": "#0000aa",
  "2": "#00aa00",
  "3": "#00aaaa",
  "4": "#aa0000",
  "5": "#aa00aa",
  "6": "#ffaa00",
  "7": "#aaaaaa",
  "8": "#555555",
  "9": "#5555ff",
  a: "#55ff55",
  b: "#55ffff",
  c: "#ff5555",
  d: "#ff55ff",
  e: "#ffff55",
  f: "#ffffff",
};

const fontTexture = loadRawImage(FONT_TEXTURE_PATH);
const nonlatinFontTexture = loadRawImage(NONLATIN_FONT_TEXTURE_PATH);
const pingTextures = {
  1: loadRawImage(PING_TEXTURE_PATHS[1]),
  2: loadRawImage(PING_TEXTURE_PATHS[2]),
  3: loadRawImage(PING_TEXTURE_PATHS[3]),
  4: loadRawImage(PING_TEXTURE_PATHS[4]),
  5: loadRawImage(PING_TEXTURE_PATHS[5]),
  unknown: loadRawImage(PING_TEXTURE_PATHS.unknown),
} as const;
let fontContext: Promise<FontContext> | null = null;
const avatarCache = new Map<string, RawImage | null>();

export async function fetchOnlinePlayers(apiUrl: string): Promise<OnlinePlayers | null> {
  try {
    const ctrl = new AbortController();
    const timeout = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
    const res = await fetch(`${apiUrl}/players`, { signal: ctrl.signal }).finally(() =>
      clearTimeout(timeout),
    );
    if (!res.ok) return null;

    return parseOnlinePlayers((await res.json()) as ApiPlayersResponse);
  } catch {
    return null;
  }
}

export async function generatePlayerListImage(
  data: OnlinePlayers,
  options: PlayerListImageOptions = {},
): Promise<Buffer> {
  const showAvatar = options.showAvatar ?? true;
  const showPing = options.showPing ?? true;
  const maxPlayers = options.maxPlayers ?? TABLIST_PLAYER_DISPLAY_LIMIT;
  const fetchAvatars = options.fetchAvatars ?? true;
  const font = await getFontContext();
  const pings = {
    1: await pingTextures[1],
    2: await pingTextures[2],
    3: await pingTextures[3],
    4: await pingTextures[4],
    5: await pingTextures[5],
    unknown: await pingTextures.unknown,
  };
  const players = [...data.players]
    .sort((a, b) => a.username.localeCompare(b.username, undefined, { sensitivity: "base" }))
    .slice(0, Math.max(0, maxPlayers));

  const parsedNames = players.map((player) =>
    parseMinecraftText(player.nickname_raw || player.nickname || player.username),
  );
  const textWidths = parsedNames.map((name) => measureText(name, font));
  let masterOffsetX = Math.max(1, ...textWidths.map((width) => width + (showAvatar ? 18 : 2)));
  masterOffsetX += showPing ? 26 : 2;

  const columnCount = Math.floor((Math.max(players.length, 1) - 1) / TABLIST_SINGLE_COLUMN_LIMIT) + 1;
  const playersPerColumn = Math.max(1, Math.ceil(players.length / columnCount));
  const listWidth = ((masterOffsetX + 2) * columnCount) + 2;
  const listHeight = playersPerColumn * 18 + 2;

  const { headerLines, footerLines } = getDecorationLines(data.count, listWidth, font);
  const decorationWidths = [...headerLines, ...footerLines].map((line) => measureText(line, font));
  const imageWidth = Math.max(listWidth, ...decorationWidths.map((width) => width + 4));
  const listY = headerLines.length > 0 ? headerLines.length * 18 + 2 : 0;
  const footerY = listY + listHeight + 1;
  const imageHeight = footerLines.length > 0
    ? footerY + footerLines.length * 18 + 2
    : listY + listHeight;
  const image = createImage(imageWidth, imageHeight, TABLIST_BACKGROUND);

  headerLines.forEach((line, index) => {
    const width = measureText(line, font);
    drawText(image, font, line, Math.floor((imageWidth - width) / 2), TEXT_Y_OFFSET + index * 18);
  });

  const listX = Math.floor((imageWidth - listWidth) / 2);
  drawRect(image, listX, listY, listWidth, listHeight, TABLIST_BACKGROUND);

  const avatars = showAvatar
    ? await Promise.all(players.map((player) => fetchAvatar(player.uuid, fetchAvatars)))
    : [];

  players.forEach((player, index) => {
    const column = Math.floor(index / playersPerColumn);
    const row = index % playersPerColumn;
    const x = listX + 2 + ((masterOffsetX + 2) * column);
    const y = listY + 2 + row * 18;

    drawRect(image, x, y, masterOffsetX, 16, TABLIST_PLAYER_BACKGROUND);

    if (showAvatar) {
      drawAvatar(image, avatars[index] ?? null, x, y);
    }

    drawText(image, font, parsedNames[index], x + (showAvatar ? 18 : 2), y);

    if (showPing) {
      drawPing(image, pings, player.ping ?? -1, x + masterOffsetX - 22, y);
    }
  });

  footerLines.forEach((line, index) => {
    const width = measureText(line, font);
    drawText(image, font, line, Math.floor((imageWidth - width) / 2), footerY + index * 18);
  });

  return sharp(image.data, {
    raw: {
      width: image.width,
      height: image.height,
      channels: 4,
    },
  }).png().toBuffer();
}

function parseOnlinePlayers(data: ApiPlayersResponse): OnlinePlayers | null {
  if (!Array.isArray(data.players)) return null;

  const players = data.players
    .map(parseOnlinePlayer)
    .filter((player): player is OnlinePlayer => player !== null);

  const count = typeof data.count === "number" && Number.isFinite(data.count)
    ? data.count
    : players.length;

  return { count, players };
}

function parseOnlinePlayer(raw: unknown): OnlinePlayer | null {
  if (!raw || typeof raw !== "object") return null;
  const player = raw as ApiPlayer;
  if (typeof player.username !== "string" || player.username.length === 0) return null;
  if (typeof player.uuid !== "string" || player.uuid.length === 0) return null;

  return {
    username: player.username,
    uuid: player.uuid,
    nickname: typeof player.nickname === "string" ? player.nickname : null,
    nickname_raw: typeof player.nickname_raw === "string" ? player.nickname_raw : null,
    ping: typeof player.ping === "number" && Number.isFinite(player.ping) ? player.ping : null,
    server: typeof player.server === "string" && player.server.length > 0 ? player.server : null,
    current_streak:
      typeof player.current_streak === "number" && Number.isFinite(player.current_streak)
        ? player.current_streak
        : 0,
  };
}

async function loadRawImage(filePath: string): Promise<RawImage> {
  const { data, info } = await sharp(filePath)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  return { data, width: info.width, height: info.height };
}

async function decodeRawPng(buffer: Buffer, width: number, height: number): Promise<RawImage> {
  const { data, info } = await sharp(buffer)
    .ensureAlpha()
    .resize(width, height, { fit: "fill", kernel: "nearest" })
    .raw()
    .toBuffer({ resolveWithObject: true });
  return { data, width: info.width, height: info.height };
}

async function getFontContext(): Promise<FontContext> {
  if (fontContext) return fontContext;

  fontContext = Promise.all([fontTexture, nonlatinFontTexture]).then(([ascii, nonlatin]) => {
    const glyphs = new Map<number, Glyph>();

    for (let code = 0; code < 256; code++) {
      if (code === 32) continue;
      const sx = (code % 16) * 8;
      const sy = Math.floor(code / 16) * 8;
      const width = scanGlyphWidth(ascii, sx, sy, 8, 8);
      if (width > 0) {
        glyphs.set(code, {
          texture: "ascii",
          sx,
          sy,
          width,
          height: 8,
          advance: Math.min(8, width + 1),
        });
      }
    }

    for (const [code, [column, row]] of NONLATIN_GLYPHS) {
      const sx = column * 8;
      const sy = row * 8;
      const width = scanGlyphWidth(nonlatin, sx, sy, 8, 8);
      if (width > 0) {
        glyphs.set(code, {
          texture: "nonlatin",
          sx,
          sy,
          width,
          height: 8,
          advance: Math.min(8, width + 1),
        });
      }
    }

    return {
      textures: { ascii, nonlatin },
      glyphs,
    };
  });

  return fontContext;
}

function scanGlyphWidth(source: RawImage, sx: number, sy: number, width: number, height: number) {
  let last = -1;
  for (let x = 0; x < width; x++) {
    for (let y = 0; y < height; y++) {
      const index = ((sy + y) * source.width + sx + x) * 4 + 3;
      if (source.data[index] > 0) {
        last = x;
        break;
      }
    }
  }
  return last + 1;
}

function createImage(width: number, height: number, color: Rgba): RawImage {
  const data = Buffer.alloc(width * height * 4);
  for (let i = 0; i < data.length; i += 4) {
    data[i] = color.r;
    data[i + 1] = color.g;
    data[i + 2] = color.b;
    data[i + 3] = color.a;
  }
  return { data, width, height };
}

function drawRect(dest: RawImage, x: number, y: number, width: number, height: number, color: Rgba) {
  for (let iy = 0; iy < height; iy++) {
    const py = y + iy;
    if (py < 0 || py >= dest.height) continue;
    for (let ix = 0; ix < width; ix++) {
      const px = x + ix;
      if (px < 0 || px >= dest.width) continue;
      setPixel(dest, px, py, color);
    }
  }
}

function drawText(
  dest: RawImage,
  font: FontContext,
  segments: TextSegment[],
  x: number,
  y: number,
) {
  drawTextPass(dest, font, shadowSegments(segments), x + 2, y + 2);
  drawTextPass(dest, font, segments, x, y);
}

function drawTextPass(
  dest: RawImage,
  font: FontContext,
  segments: TextSegment[],
  x: number,
  y: number,
) {
  let cursor = x;
  for (const segment of segments) {
    const startX = cursor;
    const color = parseHex(segment.color);
    for (let i = 0; i < segment.text.length;) {
      const code = segment.text.codePointAt(i) ?? 32;
      const char = String.fromCodePoint(code);
      if (code !== 32) drawGlyph(dest, font, code, cursor, y, color, segment.bold, segment.italic);
      cursor += getCharacterAdvance(font, code, segment.bold) * SCALE;
      i += char.length;
    }
    if (segment.strikethrough && cursor > startX) {
      drawRect(dest, startX, y + 7, cursor - startX, 2, color);
    }
  }
}

function drawGlyph(
  dest: RawImage,
  font: FontContext,
  code: number,
  x: number,
  y: number,
  color: Rgba,
  bold: boolean,
  italic: boolean,
) {
  const glyph = font.glyphs.get(code) ?? font.glyphs.get(63);
  if (!glyph) return;

  const options = {
    sx: glyph.sx,
    sy: glyph.sy,
    width: glyph.width,
    height: glyph.height,
    scale: SCALE,
    tint: color,
    italic,
  };
  drawImage(dest, font.textures[glyph.texture], x, y, options);
  if (bold) drawImage(dest, font.textures[glyph.texture], x + SCALE, y, options);
}

function getCharacterAdvance(font: FontContext, code: number, bold = false) {
  const advance = code === 32
    ? 4
    : font.glyphs.get(code)?.advance ?? font.glyphs.get(63)?.advance ?? 4;
  return advance + (bold ? 1 : 0);
}

function drawAvatar(dest: RawImage, avatar: RawImage | null, x: number, y: number) {
  if (avatar) {
    drawImage(dest, avatar, x, y, { width: 16, height: 16 });
    return;
  }

  drawRect(dest, x, y, 16, 16, parseHex("#6b4f3a"));
  drawRect(dest, x + 3, y + 4, 3, 3, parseHex("#101010"));
  drawRect(dest, x + 10, y + 4, 3, 3, parseHex("#101010"));
  drawRect(dest, x + 5, y + 10, 6, 2, parseHex("#2f1f17"));
}

function drawPing(
  dest: RawImage,
  pings: { 1: RawImage; 2: RawImage; 3: RawImage; 4: RawImage; 5: RawImage; unknown: RawImage },
  ping: number,
  x: number,
  y: number,
) {
  const texture =
    ping < 0 ? pings.unknown :
      ping < 150 ? pings[5] :
        ping < 300 ? pings[4] :
          ping < 600 ? pings[3] :
            ping < 1000 ? pings[2] :
              pings[1];
  drawImage(dest, texture, x, y, { width: 10, height: 8, scale: SCALE });
}

interface DrawImageOptions {
  sx?: number;
  sy?: number;
  width?: number;
  height?: number;
  scale?: number;
  tint?: Rgba;
  italic?: boolean;
}

function drawImage(
  dest: RawImage,
  source: RawImage,
  x: number,
  y: number,
  options: DrawImageOptions = {},
) {
  const sx = options.sx ?? 0;
  const sy = options.sy ?? 0;
  const width = options.width ?? source.width;
  const height = options.height ?? source.height;
  const scale = options.scale ?? 1;

  for (let iy = 0; iy < height * scale; iy++) {
    const py = y + iy;
    if (py < 0 || py >= dest.height) continue;
    const sourceY = sy + Math.floor(iy / scale);
    if (sourceY < 0 || sourceY >= source.height) continue;
    const italicOffset = options.italic ? Math.round(((height * scale - 1 - iy) * 4) / 14) : 0;
    for (let ix = 0; ix < width * scale; ix++) {
      const px = x + ix + italicOffset;
      if (px < 0 || px >= dest.width) continue;
      const sourceX = sx + Math.floor(ix / scale);
      if (sourceX < 0 || sourceX >= source.width) continue;
      const sourceIndex = (sourceY * source.width + sourceX) * 4;
      const alpha = source.data[sourceIndex + 3];
      if (alpha === 0) continue;
      const color = options.tint
        ? { ...options.tint, a: Math.round((alpha / 255) * options.tint.a) }
        : {
          r: source.data[sourceIndex],
          g: source.data[sourceIndex + 1],
          b: source.data[sourceIndex + 2],
          a: alpha,
        };
      blendPixel(dest, px, py, color);
    }
  }
}

async function fetchAvatar(uuid: string, shouldFetch: boolean): Promise<RawImage | null> {
  if (!shouldFetch) return null;
  if (avatarCache.has(uuid)) return avatarCache.get(uuid) ?? null;

  try {
    const ctrl = new AbortController();
    const timeout = setTimeout(() => ctrl.abort(), AVATAR_FETCH_TIMEOUT_MS);
    const res = await fetch(`https://mc-heads.net/avatar/${encodeURIComponent(uuid)}/16.png`, {
      signal: ctrl.signal,
    }).finally(() => clearTimeout(timeout));
    if (!res.ok) {
      avatarCache.set(uuid, null);
      return null;
    }

    const avatar = await decodeRawPng(Buffer.from(await res.arrayBuffer()), 16, 16);
    avatarCache.set(uuid, avatar);
    return avatar;
  } catch {
    avatarCache.set(uuid, null);
    return null;
  }
}

export function parseMinecraftText(raw: string): TextSegment[] {
  const segments: TextSegment[] = [];
  parseTextIntoSegments(
    raw,
    { color: "#ffffff", bold: false, strikethrough: false, italic: false },
    segments,
  );

  return segments.length > 0
    ? segments
    : [{ text: "", color: "#ffffff", bold: false, strikethrough: false, italic: false }];
}

function parseTextIntoSegments(
  raw: string,
  baseStyle: Omit<TextSegment, "text">,
  segments: TextSegment[],
  gradient?: { colors: Rgba[]; index: number; total: number },
) {
  const style = { ...baseStyle };

  for (let i = 0; i < raw.length;) {
    const gradientTag = readGradientTag(raw, i);
    if (gradientTag) {
      parseTextIntoSegments(
        gradientTag.inner,
        style,
        segments,
        {
          colors: gradientTag.colors.map(parseHex),
          index: 0,
          total: Math.max(1, countRenderableCharacters(gradientTag.inner)),
        },
      );
      i = gradientTag.end;
      continue;
    }

    const colorTag = readColorTag(raw, i);
    if (colorTag) {
      style.color = colorTag.color;
      i = colorTag.end;
      continue;
    }

    if (raw.slice(i).toLowerCase().startsWith("<reset>")) {
      style.color = "#ffffff";
      style.bold = false;
      style.strikethrough = false;
      style.italic = false;
      i += "<reset>".length;
      continue;
    }

    if (raw.slice(i).toLowerCase().startsWith("</gradient>")) {
      i += "</gradient>".length;
      continue;
    }

    const legacyEnd = applyLegacyFormat(raw, i, style);
    if (legacyEnd > i) {
      i = legacyEnd;
      continue;
    }

    const code = raw.codePointAt(i) ?? 32;
    const char = String.fromCodePoint(code);
    appendTextSegment(segments, char, {
      ...style,
      color: gradient ? gradientColor(gradient.colors, gradient.index++, gradient.total) : style.color,
    });
    i += char.length;
  }
}

function readGradientTag(raw: string, index: number) {
  const tagStart = raw.slice(index).toLowerCase();
  if (!tagStart.startsWith("<gradient:")) return null;

  const tagEnd = raw.indexOf(">", index);
  if (tagEnd < 0) return null;

  const colors = raw
    .slice(index + "<gradient:".length, tagEnd)
    .split(":")
    .map((color) => normalizeHex(color))
    .filter((color): color is string => color !== null);
  if (colors.length < 2) return null;

  const closeTag = "</gradient>";
  const closeIndex = raw.toLowerCase().indexOf(closeTag, tagEnd + 1);
  if (closeIndex < 0) {
    const resetIndex = raw.toLowerCase().indexOf("<reset>", tagEnd + 1);
    const end = resetIndex < 0 ? raw.length : resetIndex;
    return {
      colors,
      inner: raw.slice(tagEnd + 1, end),
      end,
    };
  }

  return {
    colors,
    inner: raw.slice(tagEnd + 1, closeIndex),
    end: closeIndex + closeTag.length,
  };
}

function readColorTag(raw: string, index: number) {
  const match = raw.slice(index).match(/^<#([0-9a-fA-F]{6})>/);
  if (!match) return null;
  return {
    color: normalizeHex(match[1]) ?? "#ffffff",
    end: index + match[0].length,
  };
}

function applyLegacyFormat(raw: string, index: number, style: Omit<TextSegment, "text">) {
  if (raw[index] !== "§" && raw[index] !== "&") return index;

  const marker = raw[index];
  const code = raw[index + 1]?.toLowerCase();
  if (code === "x" && index + 13 < raw.length) {
    let hex = "";
    for (let j = 0; j < 6; j++) {
      if (raw[index + 2 + j * 2] !== marker || !/[0-9a-f]/i.test(raw[index + 3 + j * 2])) {
        return index;
      }
      hex += raw[index + 3 + j * 2];
    }
    style.color = `#${hex.toLowerCase()}`;
    style.bold = false;
    style.strikethrough = false;
    style.italic = false;
    return index + 14;
  }

  if (code && LEGACY_COLORS[code]) {
    style.color = LEGACY_COLORS[code];
    style.bold = false;
    style.strikethrough = false;
    style.italic = false;
    return index + 2;
  }

  if (code === "l") {
    style.bold = true;
    return index + 2;
  }

  if (code === "m") {
    style.strikethrough = true;
    return index + 2;
  }

  if (code === "o") {
    style.italic = true;
    return index + 2;
  }

  if (code === "r") {
    style.color = "#ffffff";
    style.bold = false;
    style.strikethrough = false;
    style.italic = false;
    return index + 2;
  }

  return index;
}

function appendTextSegment(
  segments: TextSegment[],
  text: string,
  style: Omit<TextSegment, "text">,
) {
  const last = segments[segments.length - 1];
  if (
    last &&
    last.color === style.color &&
    last.bold === style.bold &&
    last.strikethrough === style.strikethrough &&
    last.italic === style.italic
  ) {
    last.text += text;
    return;
  }

  segments.push({ text, ...style });
}

function countRenderableCharacters(raw: string) {
  let count = 0;

  for (let i = 0; i < raw.length;) {
    const gradientTag = readGradientTag(raw, i);
    if (gradientTag) {
      count += countRenderableCharacters(gradientTag.inner);
      i = gradientTag.end;
      continue;
    }

    const colorTag = readColorTag(raw, i);
    if (colorTag) {
      i = colorTag.end;
      continue;
    }

    if (raw.slice(i).toLowerCase().startsWith("<reset>")) {
      i += "<reset>".length;
      continue;
    }

    if (raw.slice(i).toLowerCase().startsWith("</gradient>")) {
      i += "</gradient>".length;
      continue;
    }

    const legacyEnd = legacyFormatEnd(raw, i);
    if (legacyEnd > i) {
      i = legacyEnd;
      continue;
    }

    const code = raw.codePointAt(i) ?? 32;
    count++;
    i += String.fromCodePoint(code).length;
  }

  return count;
}

function legacyFormatEnd(raw: string, index: number) {
  if (raw[index] !== "§" && raw[index] !== "&") return index;
  const marker = raw[index];
  const code = raw[index + 1]?.toLowerCase();
  if (code === "x" && index + 13 < raw.length) {
    for (let j = 0; j < 6; j++) {
      if (raw[index + 2 + j * 2] !== marker || !/[0-9a-f]/i.test(raw[index + 3 + j * 2])) {
        return index;
      }
    }
    return index + 14;
  }
  return code && (LEGACY_COLORS[code] || code === "l" || code === "m" || code === "o" || code === "r")
    ? index + 2
    : index;
}

function shadowSegments(segments: TextSegment[]): TextSegment[] {
  return segments.map((segment) => ({ ...segment, color: shadowColor(segment.color) }));
}

function measureText(segments: TextSegment[], font: FontContext): number {
  let width = 0;
  for (const segment of segments) {
    for (let i = 0; i < segment.text.length;) {
      const code = segment.text.codePointAt(i) ?? 32;
      width += getCharacterAdvance(font, code, segment.bold) * SCALE;
      i += String.fromCodePoint(code).length;
    }
  }
  return width;
}

function gradientColor(colors: Rgba[], index: number, total: number) {
  if (colors.length === 1 || total <= 1) return rgbaToHex(colors[0]);

  const progress = index / (total - 1);
  const scaled = progress * (colors.length - 1);
  const start = Math.min(colors.length - 2, Math.floor(scaled));
  const localProgress = scaled - start;
  const from = colors[start];
  const to = colors[start + 1];

  return rgbaToHex({
    r: Math.round(from.r + (to.r - from.r) * localProgress),
    g: Math.round(from.g + (to.g - from.g) * localProgress),
    b: Math.round(from.b + (to.b - from.b) * localProgress),
    a: 255,
  });
}

function getDecorationLines(count: number, listWidth: number, font: FontContext) {
  const footerStatus = `&7&o( ${count} players currently connected )`;
  const titleWidth = measureText(parseMinecraftText(CRABCRAFT_TITLE), font);
  const footerWidth = measureText(parseMinecraftText(footerStatus), font);
  const targetWidth = Math.max(
    listWidth + DECORATION_PADDING,
    footerWidth + DECORATION_PADDING,
    titleWidth + TITLE_MIN_PADDING,
  );
  const headerRuleSpaces = getHeaderRuleSpaceCount(targetWidth, titleWidth, font);
  const footerRuleSpaces = getFooterRuleSpaceCount(targetWidth, font);

  return {
    headerLines: [
      "",
      getHeaderRuleLine(headerRuleSpaces),
      SEASON_LINE,
      "",
    ].map(parseMinecraftText),
    footerLines: [
      "",
      footerStatus,
      "",
      getFooterRuleLine(footerRuleSpaces),
      "",
    ].map(parseMinecraftText),
  };
}

function getHeaderRuleLine(ruleSpaces: number) {
  const rule = " ".repeat(ruleSpaces);
  return `   <gradient:#FFFF55:#FFAA00>&m${rule}</gradient>   ${CRABCRAFT_TITLE}   <gradient:#FFAA00:#FFFF55>&m${rule}<reset>   `;
}

function getFooterRuleLine(ruleSpaces: number) {
  return `   <gradient:#FFFF55:#FFAA00:#FFFF55>&m${" ".repeat(ruleSpaces)}<reset>   `;
}

function getHeaderRuleSpaceCount(targetWidth: number, titleWidth: number, font: FontContext) {
  const spaceWidth = getCharacterAdvance(font, 32) * SCALE;
  const fixedSpaces = 12;
  const availableWidth = targetWidth - titleWidth - fixedSpaces * spaceWidth;
  return Math.max(HEADER_RULE_MIN_SPACES, Math.round(availableWidth / (spaceWidth * 2)));
}

function getFooterRuleSpaceCount(targetWidth: number, font: FontContext) {
  const spaceWidth = getCharacterAdvance(font, 32) * SCALE;
  const fixedSpaces = 6;
  const availableWidth = targetWidth - fixedSpaces * spaceWidth;
  return Math.max(FOOTER_RULE_MIN_SPACES, Math.round(availableWidth / spaceWidth));
}

interface Rgba {
  r: number;
  g: number;
  b: number;
  a: number;
}

function parseHex(hex: string): Rgba {
  const normalized = normalizeHex(hex) ?? "#ffffff";
  return {
    r: parseInt(normalized.slice(1, 3), 16),
    g: parseInt(normalized.slice(3, 5), 16),
    b: parseInt(normalized.slice(5, 7), 16),
    a: 255,
  };
}

function normalizeHex(value: string) {
  const hex = value.startsWith("#") ? value.slice(1) : value;
  return /^[0-9a-fA-F]{6}$/.test(hex) ? `#${hex.toLowerCase()}` : null;
}

function rgbaToHex(color: Rgba) {
  return `#${[color.r, color.g, color.b]
    .map((part) => part.toString(16).padStart(2, "0"))
    .join("")}`;
}

function shadowColor(color: string): string {
  const value = parseHex(color);
  const red = Math.floor(value.r * CHAT_COLOR_BACKGROUND_FACTOR);
  const green = Math.floor(value.g * CHAT_COLOR_BACKGROUND_FACTOR);
  const blue = Math.floor(value.b * CHAT_COLOR_BACKGROUND_FACTOR);
  return `#${[red, green, blue].map((part) => part.toString(16).padStart(2, "0")).join("")}`;
}

function setPixel(dest: RawImage, x: number, y: number, color: Rgba) {
  const index = (y * dest.width + x) * 4;
  dest.data[index] = color.r;
  dest.data[index + 1] = color.g;
  dest.data[index + 2] = color.b;
  dest.data[index + 3] = color.a;
}

function blendPixel(dest: RawImage, x: number, y: number, source: Rgba) {
  const index = (y * dest.width + x) * 4;
  if (source.a >= 255) {
    dest.data[index] = source.r;
    dest.data[index + 1] = source.g;
    dest.data[index + 2] = source.b;
    dest.data[index + 3] = 255;
    return;
  }

  const sourceAlpha = source.a / 255;
  const destAlpha = dest.data[index + 3] / 255;
  const outAlpha = sourceAlpha + destAlpha * (1 - sourceAlpha);
  if (outAlpha <= 0) return;

  dest.data[index] = Math.round((source.r * sourceAlpha + dest.data[index] * destAlpha * (1 - sourceAlpha)) / outAlpha);
  dest.data[index + 1] = Math.round((source.g * sourceAlpha + dest.data[index + 1] * destAlpha * (1 - sourceAlpha)) / outAlpha);
  dest.data[index + 2] = Math.round((source.b * sourceAlpha + dest.data[index + 2] * destAlpha * (1 - sourceAlpha)) / outAlpha);
  dest.data[index + 3] = Math.round(outAlpha * 255);
}
