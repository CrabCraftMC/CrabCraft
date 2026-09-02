/**
 * Generate the player-facing Block, Item, and Mob Hunt catalogues from
 * Minecraft Wiki's current stable Java Edition lists and image files.
 *
 * Usage: bun run scripts/generate-hunt-catalogues.ts
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { dirname, resolve } from "path";
import { fileURLToPath } from "url";
import sharp from "sharp";

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = resolve(__dirname, "..");
const WIKI_API = "https://minecraft.wiki/api.php";
const WIKI_FILE = "https://minecraft.wiki/Special:Redirect/file";
const IMAGE_CACHE = resolve(tmpdir(), "crabcraft-hunt-wiki-images");
const ATLAS_COLUMNS = 32;
const CELL_SIZE = 32;

type HuntKind = "block" | "item" | "mob";

type WikiPage = {
  title: string;
  revision: number;
  content: string;
};

type WikiEntry = {
  name: string;
  article: string;
  imageCandidates: string[];
};

type CatalogueEntry = {
  id: string;
  name: string;
  aliases: string[];
  article: string;
  imageCandidates: string[];
};

type FunctionalGroup = {
  id: string;
  name: string;
  representative: string;
  members: Set<string>;
};

const COLOURS = [
  "Black",
  "Blue",
  "Brown",
  "Cyan",
  "Gray",
  "Green",
  "Light Blue",
  "Light Gray",
  "Lime",
  "Magenta",
  "Orange",
  "Pink",
  "Purple",
  "Red",
  "White",
  "Yellow",
] as const;

const colourMembers = (suffix: string) =>
  new Set(COLOURS.map((colour) => `${colour} ${suffix}`));

const suffixedMembers = (prefixes: readonly string[], suffix: string) =>
  new Set(prefixes.map((prefix) => `${prefix} ${suffix}`));

const BLOCK_GROUPS: FunctionalGroup[] = [
  { id: "banner", name: "Banner", representative: "White Banner", members: colourMembers("Banner") },
  { id: "bed", name: "Bed", representative: "White Bed", members: colourMembers("Bed") },
  {
    id: "candle",
    name: "Candle",
    representative: "Candle",
    members: new Set(["Candle", ...colourMembers("Candle")]),
  },
  { id: "carpet", name: "Carpet", representative: "White Carpet", members: colourMembers("Carpet") },
  { id: "concrete", name: "Concrete", representative: "White Concrete", members: colourMembers("Concrete") },
  {
    id: "concrete-powder",
    name: "Concrete Powder",
    representative: "White Concrete Powder",
    members: colourMembers("Concrete Powder"),
  },
  {
    id: "glass",
    name: "Glass",
    representative: "Glass",
    members: new Set(["Glass", ...colourMembers("Stained Glass")]),
  },
  {
    id: "glass-pane",
    name: "Glass Pane",
    representative: "Glass Pane",
    members: new Set(["Glass Pane", ...colourMembers("Stained Glass Pane")]),
  },
  {
    id: "glazed-terracotta",
    name: "Glazed Terracotta",
    representative: "White Glazed Terracotta",
    members: colourMembers("Glazed Terracotta"),
  },
  {
    id: "shulker-box",
    name: "Shulker Box",
    representative: "Shulker Box",
    members: new Set(["Shulker Box", ...colourMembers("Shulker Box")]),
  },
  {
    id: "terracotta",
    name: "Terracotta",
    representative: "Terracotta",
    members: new Set(["Terracotta", ...colourMembers("Terracotta")]),
  },
  { id: "wool", name: "Wool", representative: "White Wool", members: colourMembers("Wool") },
];

const ITEM_GROUPS: FunctionalGroup[] = [
  {
    id: "armor-trim",
    name: "Armor Trim",
    representative: "Bolt Armor Trim",
    members: suffixedMembers(
      [
        "Bolt",
        "Coast",
        "Dune",
        "Eye",
        "Flow",
        "Host",
        "Raiser",
        "Rib",
        "Sentry",
        "Shaper",
        "Silence",
        "Snout",
        "Spire",
        "Tide",
        "Vex",
        "Ward",
        "Wayfinder",
        "Wild",
      ],
      "Armor Trim",
    ),
  },
  {
    id: "banner-pattern",
    name: "Banner Pattern",
    representative: "Creeper Charge Banner Pattern",
    members: suffixedMembers(
      [
        "Bordure Indented",
        "Creeper Charge",
        "Field Masoned",
        "Flow",
        "Flower Charge",
        "Globe",
        "Guster",
        "Skull Charge",
        "Snout",
        "Thing",
      ],
      "Banner Pattern",
    ),
  },
  {
    id: "boat",
    name: "Boat",
    representative: "Oak Boat",
    members: new Set([
      "Acacia Boat",
      "Bamboo Raft",
      "Birch Boat",
      "Cherry Boat",
      "Dark Oak Boat",
      "Jungle Boat",
      "Mangrove Boat",
      "Oak Boat",
      "Pale Oak Boat",
      "Spruce Boat",
    ]),
  },
  {
    id: "boat-with-chest",
    name: "Boat with Chest",
    representative: "Oak Boat with Chest",
    members: new Set([
      "Acacia Boat with Chest",
      "Bamboo Raft with Chest",
      "Birch Boat with Chest",
      "Cherry Boat with Chest",
      "Dark Oak Boat with Chest",
      "Jungle Boat with Chest",
      "Mangrove Boat with Chest",
      "Oak Boat with Chest",
      "Pale Oak Boat with Chest",
      "Spruce Boat with Chest",
    ]),
  },
  {
    id: "bundle",
    name: "Bundle",
    representative: "Bundle",
    members: new Set(["Bundle", ...colourMembers("Bundle")]),
  },
  {
    id: "dye",
    name: "Dye",
    representative: "White Dye",
    members: colourMembers("Dye"),
  },
  {
    id: "harness",
    name: "Harness",
    representative: "White Harness",
    members: colourMembers("Harness"),
  },
  {
    id: "pottery-sherd",
    name: "Pottery Sherd",
    representative: "Angler Pottery Sherd",
    members: suffixedMembers(
      [
        "Angler",
        "Archer",
        "Arms Up",
        "Blade",
        "Brewer",
        "Burn",
        "Danger",
        "Explorer",
        "Flow",
        "Friend",
        "Guster",
        "Heart",
        "Heartbreak",
        "Howl",
        "Miner",
        "Mourner",
        "Plenty",
        "Prize",
        "Scrape",
        "Sheaf",
        "Shelter",
        "Skull",
        "Snort",
      ],
      "Pottery Sherd",
    ),
  },
];

const NAME_ALIASES: Record<string, string[]> = {
  "Block of Amethyst": ["Amethyst Block"],
  "Block of Quartz": ["Quartz Block"],
  "Hay Bale": ["Hay Block"],
  "Lapis Lazuli Ore": ["Lapis Ore"],
};

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    headers: { "User-Agent": "CrabCraft Hunt catalogue generator" },
  });
  if (!response.ok) throw new Error(`${response.status} while fetching ${url}`);
  return (await response.json()) as T;
}

async function fetchWikiPage(title: string): Promise<WikiPage> {
  const params = new URLSearchParams({
    action: "query",
    format: "json",
    formatversion: "2",
    prop: "revisions",
    rvprop: "ids|content",
    rvslots: "main",
    titles: title,
  });
  const result = await fetchJson<{
    query: {
      pages: Array<{
        title: string;
        revisions?: Array<{
          revid: number;
          slots: { main: { content: string } };
        }>;
      }>;
    };
  }>(`${WIKI_API}?${params}`);
  const page = result.query.pages[0];
  const revision = page?.revisions?.[0];
  if (!page || !revision) throw new Error(`Missing Minecraft Wiki page: ${title}`);
  return {
    title: page.title,
    revision: revision.revid,
    content: revision.slots.main.content,
  };
}

function normaliseName(value: string): string {
  return value
    .replace(/<!--.*?-->/g, "")
    .replace(/''+/g, "")
    .replace(/\{\{!\}\}/g, "|")
    .trim();
}

function slugify(value: string): string {
  return value
    .toLocaleLowerCase("en-GB")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

function isStableJavaLine(line: string): boolean {
  const lower = line.toLocaleLowerCase("en-GB");
  return (
    !lower.includes("{{upcoming") &&
    !lower.includes("{{exclusive|bedrock") &&
    !lower.includes("{{exclusive|education") &&
    !/\{\{only\|(be|bedrock|ee|education)(?:\||\})/.test(lower)
  );
}

function parseBlocks(content: string): WikiEntry[] {
  const start = content.indexOf('<section begin="list"');
  const end = content.indexOf("=== Technical blocks ===");
  if (start < 0 || end < 0) throw new Error("Minecraft Wiki block list markers changed");

  const entries: WikiEntry[] = [];
  for (const line of content.slice(start, end).split("\n")) {
    if (!isStableJavaLine(line)) continue;
    const match = line.match(
      /^\*\[\[File:([^|\]]+)(?:\|[^\]]*)?\]\]\s*\[\[([^|\]]+)(?:\|([^\]]+))?\]\]/,
    );
    if (!match) continue;
    const [, image, article, display] = match;
    entries.push({
      name: normaliseName(display ?? article),
      article: normaliseName(article),
      imageCandidates: [image],
    });
  }
  return entries;
}

function parseTemplateName(rawArguments: string): string | null {
  const argumentsList = rawArguments.split("|").map(normaliseName);
  const positional = argumentsList.filter(
    (argument) => argument && !/^[a-z0-9 _/-]+=/i.test(argument),
  );
  return positional.at(-1) ?? null;
}

function parseItems(content: string, blockNames: Set<string>): WikiEntry[] {
  const start = content.indexOf("=== In the game ===");
  const end = content.indexOf("=== ''Minecraft Education'' items ===");
  if (start < 0 || end < 0) throw new Error("Minecraft Wiki item list headings changed");

  const entries: WikiEntry[] = [];
  for (const line of content.slice(start, end).split("\n")) {
    if (!isStableJavaLine(line)) continue;
    for (const match of line.matchAll(/\{\{(?:ItemLink|InvLink)\|([^{}\n]+)\}\}/g)) {
      const name = parseTemplateName(match[1]);
      if (!name || blockNames.has(name.toLocaleLowerCase("en-GB"))) continue;
      entries.push({
        name,
        article: name,
        imageCandidates: [
          `Invicon ${name}.png`,
          `Invicon ${name.replace(/^Music Disc \((.*)\)$/, "Music Disc $1")}.png`,
          `Invicon ${name.replace(/[()]/g, "")}.png`,
          `${name}.png`,
          `Invicon ${name}.gif`,
        ],
      });
    }
  }
  return entries;
}

function parseMobs(content: string): WikiEntry[] {
  const start = content.indexOf("=== Passive mobs ===");
  const end = content.indexOf("=== Creators or education mobs ===");
  if (start < 0 || end < 0) throw new Error("Minecraft Wiki mob list headings changed");

  const entries: WikiEntry[] = [];
  for (const line of content.slice(start, end).split("\n")) {
    if (!isStableJavaLine(line)) continue;
    const nameMatch = line.match(/^\{\{Mob icon\|([^|}\n]+)/);
    if (!nameMatch || nameMatch[1] === "start" || nameMatch[1] === "end") continue;
    const name = normaliseName(nameMatch[1]);
    const explicitImage = line.match(/\|image=([^|}]+)/)?.[1]?.trim();
    entries.push({
      name,
      article: name,
      imageCandidates: [
        ...(explicitImage ? [explicitImage] : []),
        `EntitySprite ${name.toLocaleLowerCase("en-GB")}.png`,
        `EntitySprite ${name.replace(/^Trader /, "").toLocaleLowerCase("en-GB")}.png`,
        `${name}.png`,
        `${name}.gif`,
      ],
    });
  }
  return entries;
}

function uniqueEntries(entries: WikiEntry[]): WikiEntry[] {
  return [...new Map(entries.map((entry) => [entry.name, entry])).values()];
}

function applyFunctionalGroups(
  entries: WikiEntry[],
  groups: FunctionalGroup[],
): CatalogueEntry[] {
  const entryByName = new Map(entries.map((entry) => [entry.name, entry]));
  const groupByMember = new Map(
    groups.flatMap((group) =>
      [...group.members].map((member) => [member, group] as const),
    ),
  );
  const groupedMembers = new Map<string, WikiEntry[]>();

  for (const entry of entries) {
    const group = groupByMember.get(entry.name);
    const id = group?.id ?? slugify(entry.name);
    groupedMembers.set(id, [...(groupedMembers.get(id) ?? []), entry]);
  }

  const catalogue = [...groupedMembers].map(([id, members]) => {
    const group = groups.find((candidate) => candidate.id === id);
    const representative = group
      ? entryByName.get(group.representative) ?? members[0]
      : members[0];
    const name = group?.name ?? representative.name;
    const aliases = new Set([
      ...members.map((member) => member.name),
      ...(NAME_ALIASES[name] ?? []),
    ]);
    aliases.delete(name);
    return {
      id,
      name,
      aliases: [...aliases].sort((a, b) => a.localeCompare(b, "en-GB")),
      article: representative.article,
      imageCandidates: representative.imageCandidates,
    };
  });
  return catalogue.sort((a, b) => a.name.localeCompare(b.name, "en-GB"));
}

async function fetchImage(candidates: string[]): Promise<Buffer> {
  const failures: string[] = [];
  for (const candidate of candidates) {
    const cachePath = resolve(
      IMAGE_CACHE,
      `${Bun.hash(candidate).toString(16)}.image`,
    );
    if (existsSync(cachePath)) return readFileSync(cachePath);
    const url = `${WIKI_FILE}/${encodeURIComponent(candidate.replaceAll(" ", "_"))}`;
    for (let attempt = 1; attempt <= 6; attempt += 1) {
      const response = await fetch(url, {
        redirect: "follow",
        headers: { "User-Agent": "CrabCraft Hunt catalogue generator" },
      });
      if (
        response.ok &&
        response.headers.get("content-type")?.startsWith("image/")
      ) {
        const buffer = Buffer.from(await response.arrayBuffer());
        if (buffer.byteLength <= 20 * 1024 * 1024) {
          mkdirSync(IMAGE_CACHE, { recursive: true });
          writeFileSync(cachePath, buffer);
          return buffer;
        }
        failures.push(`${candidate}: ${buffer.byteLength} bytes`);
        break;
      }
      failures.push(`${candidate}: ${response.status}`);
      if (response.status !== 429 && response.status < 500) break;
      await Bun.sleep(attempt * 750);
    }
  }
  throw new Error(`No Minecraft Wiki image found: ${failures.join(", ")}`);
}

async function writeCatalogue(
  kind: HuntKind,
  version: string,
  page: WikiPage,
  catalogue: CatalogueEntry[],
) {
  const previews: Buffer[] = new Array(catalogue.length);
  const batchSize = 4;
  for (let index = 0; index < catalogue.length; index += batchSize) {
    const batch = catalogue.slice(index, index + batchSize);
    await Promise.all(
      batch.map(async (entry, offset) => {
        const source = await fetchImage(entry.imageCandidates);
        previews[index + offset] = await sharp(source, { animated: false })
          .resize(CELL_SIZE, CELL_SIZE, {
            fit: "contain",
            kernel: "nearest",
            background: { r: 0, g: 0, b: 0, alpha: 0 },
          })
          .png()
          .toBuffer();
      }),
    );
    process.stdout.write(
      `\rPrepared ${Math.min(index + batchSize, catalogue.length)}/${catalogue.length} ${kind} previews`,
    );
    await Bun.sleep(50);
  }

  const atlasPath = resolve(WEB_ROOT, `public/textures/hunt/${kind}s.webp`);
  mkdirSync(dirname(atlasPath), { recursive: true });
  await sharp({
    create: {
      width: ATLAS_COLUMNS * CELL_SIZE,
      height: Math.ceil(catalogue.length / ATLAS_COLUMNS) * CELL_SIZE,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite(
      previews.map((input, index) => ({
        input,
        left: (index % ATLAS_COLUMNS) * CELL_SIZE,
        top: Math.floor(index / ATLAS_COLUMNS) * CELL_SIZE,
      })),
    )
    .webp({ lossless: true, effort: 6 })
    .toFile(atlasPath);

  const iconAnswers: Record<HuntKind, string> = {
    block: "Grass Block",
    item: "Diamond",
    mob: "Creeper",
  };
  const iconIndex = catalogue.findIndex((entry) => entry.name === iconAnswers[kind]);
  if (iconIndex < 0) throw new Error(`Missing ${kind} navigation icon`);
  const iconPath = resolve(WEB_ROOT, `public/textures/hunt/icons/${kind}.webp`);
  mkdirSync(dirname(iconPath), { recursive: true });
  await sharp(previews[iconIndex]).resize(48, 48, { kernel: "nearest" }).webp({ lossless: true }).toFile(iconPath);

  const cataloguePath = resolve(
    WEB_ROOT,
    `src/data/${kind}-hunt-${kind}s.json`,
  );
  writeFileSync(
    cataloguePath,
    `${JSON.stringify(
      {
        version,
        source: `https://minecraft.wiki/w/${page.title.replaceAll(" ", "_")}`,
        sourceRevision: page.revision,
        entries: catalogue.map(({ imageCandidates: _, ...entry }, sprite) => ({
          ...entry,
          sprite,
        })),
      },
      null,
      2,
    )}\n`,
  );
  console.log(`\nGenerated ${catalogue.length} ${kind} entries.`);
}

async function main() {
  const [versionPage, blockPage, itemPage, mobPage] = await Promise.all([
    fetchWikiPage("Template:Version"),
    fetchWikiPage("Block"),
    fetchWikiPage("Item"),
    fetchWikiPage("Mob"),
  ]);
  const version = versionPage.content.match(/^\| java = ([^\n]+)$/m)?.[1]?.trim();
  if (!version) throw new Error("Minecraft Wiki stable Java version marker changed");

  const rawBlocks = uniqueEntries(parseBlocks(blockPage.content));
  const blockNames = new Set(
    rawBlocks.map((entry) => entry.name.toLocaleLowerCase("en-GB")),
  );
  const catalogues = {
    block: applyFunctionalGroups(rawBlocks, BLOCK_GROUPS),
    item: applyFunctionalGroups(
      uniqueEntries(parseItems(itemPage.content, blockNames)),
      ITEM_GROUPS,
    ),
    mob: applyFunctionalGroups(uniqueEntries(parseMobs(mobPage.content)), []),
  };

  await writeCatalogue("block", version, blockPage, catalogues.block);
  await writeCatalogue("item", version, itemPage, catalogues.item);
  await writeCatalogue("mob", version, mobPage, catalogues.mob);
  console.log(`Minecraft Java Edition ${version}, sourced from Minecraft Wiki.`);
}

main();
