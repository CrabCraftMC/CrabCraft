/**
 * Generate Block Hunt's complete, player-facing block catalogue and preview atlas.
 *
 * The registry and names come from Minecraft 26.2 generated data. Block renders
 * come from the Minecraft Property Encyclopedia where available, with official
 * Minecraft textures as a fallback for newer entries.
 *
 * Usage: bun run scripts/generate-block-hunt-catalogue.ts
 */

import { mkdirSync, readFileSync, writeFileSync } from "fs";
import { dirname, resolve } from "path";
import { fileURLToPath } from "url";
import sharp from "sharp";

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = resolve(__dirname, "..");
const ATLAS_COLUMNS = 32;
const CELL_SIZE = 32;

const MCMETA_REGISTRIES =
  "https://raw.githubusercontent.com/misode/mcmeta/26.2-registries";
const MCMETA_ASSETS =
  "https://raw.githubusercontent.com/misode/mcmeta/26.2-assets/assets/minecraft";
const ENCYCLOPEDIA_REVISION = "4e57b3b2f738c8a1e34257739312455895a0d9fe";
const ENCYCLOPEDIA_SPRITES =
  `https://raw.githubusercontent.com/JoakimThorsen/MCPropertyEncyclopedia/${ENCYCLOPEDIA_REVISION}/assets/sprites`;

const COLOURS = [
  "black",
  "blue",
  "brown",
  "cyan",
  "gray",
  "green",
  "light_blue",
  "light_gray",
  "lime",
  "magenta",
  "orange",
  "pink",
  "purple",
  "red",
  "white",
  "yellow",
] as const;

type PaletteBlock = {
  id: string;
  name: string;
};

type CatalogueEntry = {
  id: string;
  name: string;
  aliases: string[];
  representative: string;
  members: string[];
};

type GeneratedEntry = Omit<CatalogueEntry, "representative" | "members"> & {
  sprite: number;
};

type FunctionalGroup = {
  id: string;
  name: string;
  representative: string;
  members: Set<string>;
};

const colourMembers = (suffix: string) =>
  new Set(COLOURS.map((colour) => `${colour}_${suffix}`));

const functionalGroups: FunctionalGroup[] = [
  {
    id: "banner",
    name: "Banner",
    representative: "white_banner",
    members: colourMembers("banner"),
  },
  {
    id: "bed",
    name: "Bed",
    representative: "white_bed",
    members: colourMembers("bed"),
  },
  {
    id: "candle",
    name: "Candle",
    representative: "candle",
    members: new Set(["candle", ...colourMembers("candle")]),
  },
  {
    id: "carpet",
    name: "Carpet",
    representative: "white_carpet",
    members: colourMembers("carpet"),
  },
  {
    id: "concrete",
    name: "Concrete",
    representative: "white_concrete",
    members: colourMembers("concrete"),
  },
  {
    id: "concrete_powder",
    name: "Concrete Powder",
    representative: "white_concrete_powder",
    members: colourMembers("concrete_powder"),
  },
  {
    id: "glass",
    name: "Glass",
    representative: "glass",
    members: new Set(["glass", ...colourMembers("stained_glass")]),
  },
  {
    id: "glass_pane",
    name: "Glass Pane",
    representative: "glass_pane",
    members: new Set(["glass_pane", ...colourMembers("stained_glass_pane")]),
  },
  {
    id: "glazed_terracotta",
    name: "Glazed Terracotta",
    representative: "white_glazed_terracotta",
    members: colourMembers("glazed_terracotta"),
  },
  {
    id: "shulker_box",
    name: "Shulker Box",
    representative: "shulker_box",
    members: new Set(["shulker_box", ...colourMembers("shulker_box")]),
  },
  {
    id: "terracotta",
    name: "Terracotta",
    representative: "terracotta",
    members: new Set(["terracotta", ...colourMembers("terracotta")]),
  },
  {
    id: "wool",
    name: "Wool",
    representative: "white_wool",
    members: colourMembers("wool"),
  },
];

const groupByMember = new Map(
  functionalGroups.flatMap((group) =>
    [...group.members].map((member) => [member, group] as const),
  ),
);

const spriteOverrides: Record<string, string> = {
  amethyst_block: "block-of-amethyst",
  bamboo_block: "block-of-bamboo",
  coal_block: "block-of-coal",
  comparator: "redstone-comparator",
  copper_block: "block-of-copper",
  crimson_fungus: "crimson-fungi",
  crimson_stem: "crimson-hyphae",
  deepslate_lapis_ore: "deepslate-lapis-lazuli-ore",
  diamond_block: "block-of-diamond",
  dried_ghast: "dried-ghast-state-1-front",
  emerald_block: "block-of-emerald",
  gold_block: "block-of-gold",
  hay_block: "hay-bale",
  iron_block: "block-of-iron",
  lapis_block: "block-of-lapis-lazuli",
  lapis_ore: "lapis-lazuli-ore",
  large_amethyst_bud: "amethyst-bud",
  netherite_block: "block-of-netherite",
  petrified_oak_slab: "oak-slab",
  quartz_block: "block-of-quartz",
  raw_copper_block: "block-of-raw-copper",
  raw_gold_block: "block-of-raw-gold",
  raw_iron_block: "block-of-raw-iron",
  redstone_block: "block-of-redstone",
  repeater: "redstone-repeater",
  resin_block: "block-of-resin",
  smooth_quartz: "smooth-quartz-block",
  spawner: "monster-spawner",
  stripped_bamboo_block: "block-of-stripped-bamboo",
  target: "target-block",
  test_block: "test-block-start",
  vine: "vines",
  warped_fungus: "warped-fungi",
  warped_stem: "warped-hyphae",
  wheat: "wheat-crops",
};

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${response.status} while fetching ${url}`);
  return (await response.json()) as T;
}

async function fetchImage(url: string): Promise<Buffer | null> {
  const response = await fetch(url);
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`${response.status} while fetching ${url}`);
  const buffer = Buffer.from(await response.arrayBuffer());
  if (buffer.byteLength > 2 * 1024 * 1024) {
    throw new Error(`Preview exceeds 2 MiB: ${url}`);
  }
  return buffer;
}

function officialName(id: string, language: Record<string, string>): string {
  return (
    language[`block.minecraft.${id}`] ??
    language[`item.minecraft.${id}`] ??
    id
      .split("_")
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(" ")
  );
}

function encyclopediaSpriteCandidates(id: string): string[] {
  const candidates = new Set<string>();
  const addId = (candidateId: string) => {
    const override = spriteOverrides[candidateId];
    if (override) candidates.add(override);
    candidates.add(candidateId.replaceAll("_", "-"));

    const shelf = candidateId.match(/^(.+)_shelf$/);
    if (shelf) candidates.add(`${shelf[1].replaceAll("_", "-")}-shelf-front`);

    const wood = candidateId.match(/^(stripped_)?(.+)_wood$/);
    if (wood) {
      const stripped = wood[1] ? "stripped-" : "";
      candidates.add(`${stripped}${wood[2].replaceAll("_", "-")}-log`);
    }
  };

  addId(id);
  if (id.startsWith("waxed_")) addId(id.slice("waxed_".length));
  return [...candidates];
}

function officialTextureCandidates(id: string): string[] {
  const candidates = new Set<string>();
  const addBlock = (texture: string) => candidates.add(`block/${texture}`);
  const base = id.replace(/_(slab|stairs|wall)$/, "");

  addBlock(id);
  addBlock(base);
  if (base.endsWith("_brick")) addBlock(`${base}s`);

  if (id.startsWith("waxed_")) {
    const unwaxed = id.slice("waxed_".length);
    const unwaxedBase = unwaxed.replace(/_(slab|stairs|wall)$/, "");
    addBlock(unwaxed);
    addBlock(unwaxedBase);
    if (unwaxedBase.endsWith("_brick")) addBlock(`${unwaxedBase}s`);
  }

  const shelf = id.match(/^(.+)_shelf$/);
  if (shelf) addBlock(`${shelf[1]}_shelf_front`);

  const wood = id.match(/^(stripped_)?(.+)_wood$/);
  if (wood) addBlock(`${wood[1] ?? ""}${wood[2]}_log`);

  if (id === "sulfur_spike") candidates.add("item/sulfur_spike");
  return [...candidates];
}

async function loadPreview(id: string): Promise<Buffer> {
  for (const slug of encyclopediaSpriteCandidates(id)) {
    const image = await fetchImage(
      `${ENCYCLOPEDIA_SPRITES}/BlockSprite_${slug}.png`,
    );
    if (image) return image;
  }

  for (const texture of officialTextureCandidates(id)) {
    const image = await fetchImage(`${MCMETA_ASSETS}/textures/${texture}.png`);
    if (image) return image;
  }

  throw new Error(`No preview found for ${id}`);
}

async function main() {
  const [blockIds, itemIds, language] = await Promise.all([
    fetchJson<string[]>(`${MCMETA_REGISTRIES}/block/data.min.json`),
    fetchJson<string[]>(`${MCMETA_REGISTRIES}/item/data.min.json`),
    fetchJson<Record<string, string>>(`${MCMETA_ASSETS}/lang/en_us.json`),
  ]);
  const itemSet = new Set(itemIds);
  const playerFacingIds = blockIds.filter((id) => itemSet.has(id));
  const palette = JSON.parse(
    readFileSync(resolve(WEB_ROOT, "src/data/blocks.json"), "utf8"),
  ) as PaletteBlock[];
  const legacyNames = new Map<string, string[]>();
  for (const block of palette) {
    legacyNames.set(block.id, [
      ...(legacyNames.get(block.id) ?? []),
      block.name,
    ]);
  }

  const groupedMembers = new Map<string, string[]>();
  for (const id of playerFacingIds) {
    const group = groupByMember.get(id);
    const catalogueId = group?.id ?? id;
    groupedMembers.set(catalogueId, [
      ...(groupedMembers.get(catalogueId) ?? []),
      id,
    ]);
  }

  const catalogue: CatalogueEntry[] = [...groupedMembers].map(
    ([id, members]) => {
      const group = functionalGroups.find((candidate) => candidate.id === id);
      const name = group?.name ?? officialName(id, language);
      const aliases = new Set<string>();

      for (const member of members) {
        aliases.add(officialName(member, language));
        for (const legacyName of legacyNames.get(member) ?? []) {
          aliases.add(legacyName);
        }
      }
      aliases.delete(name);

      return {
        id,
        name,
        aliases: [...aliases].sort((a, b) => a.localeCompare(b, "en-GB")),
        representative: group?.representative ?? id,
        members,
      };
    },
  );
  catalogue.sort((a, b) => a.name.localeCompare(b.name, "en-GB"));

  const names = new Set<string>();
  for (const entry of catalogue) {
    if (names.has(entry.name)) throw new Error(`Duplicate name: ${entry.name}`);
    names.add(entry.name);
  }

  const previews: Buffer[] = new Array(catalogue.length);
  const batchSize = 24;
  for (let index = 0; index < catalogue.length; index += batchSize) {
    const batch = catalogue.slice(index, index + batchSize);
    await Promise.all(
      batch.map(async (entry, offset) => {
        const source = await loadPreview(entry.representative);
        previews[index + offset] = await sharp(source)
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
      `\rPrepared ${Math.min(index + batchSize, catalogue.length)}/${catalogue.length} previews`,
    );
  }

  const atlasRows = Math.ceil(catalogue.length / ATLAS_COLUMNS);
  const atlasPath = resolve(
    WEB_ROOT,
    "public/textures/block-hunt/blocks.webp",
  );
  mkdirSync(dirname(atlasPath), { recursive: true });
  await sharp({
    create: {
      width: ATLAS_COLUMNS * CELL_SIZE,
      height: atlasRows * CELL_SIZE,
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

  const output: GeneratedEntry[] = catalogue.map(
    ({ id, name, aliases }, sprite) => ({ id, name, aliases, sprite }),
  );
  const cataloguePath = resolve(
    WEB_ROOT,
    "src/data/block-hunt-blocks.json",
  );
  writeFileSync(cataloguePath, `${JSON.stringify(output, null, 2)}\n`);

  console.log(
    `\nGenerated ${output.length} functional entries from ${playerFacingIds.length} block items.`,
  );
}

main();
