import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { Renderer, type FontLoader } from "@takumi-rs/core";
import { container, image, text } from "@takumi-rs/helpers";
import { getSvgPath } from "figma-squircle";
import type { BingoCardRecord } from "@crabcraft/db/queries/bingo";

const dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.resolve(dirname, "../../assets/player-card");
const LOGO = path.resolve(dirname, "../../../web/public/logo.png");
const WIDTH = 1_200;
const HEIGHT = 1_110;
const GRID_WIDTH = 1_120;
const GRID_GAP = 10;
const CELL_WIDTH = (GRID_WIDTH - GRID_GAP * 3) / 4;
const CELL_HEIGHT = 190;
const PAPER = "#1a1412";
const PAPER_2 = "#231c17";
const LINE = "#3a2e24";
const FOREGROUND = "#f5f0eb";
const ORANGE_FROM = "#F97316";
const ORANGE_TO = "#FB923C";
const COMPLETE = "#77dd77";
const SANS = "Unbounded";
const logoUri = `data:image/png;base64,${fs.readFileSync(LOGO).toString("base64")}`;

const fonts: FontLoader[] = [
  { name: SANS, data: fs.readFileSync(path.join(ASSETS, "Unbounded.ttf")) },
];
const CompatibleRenderer = Renderer as unknown as {
  new(options?: { loadDefaultFonts?: boolean; fonts?: FontLoader[] }): Renderer;
};
const renderer = new CompatibleRenderer({
  loadDefaultFonts: true,
  fonts,
});

function squircle(width: number, height: number, radius: number) {
  const d = getSvgPath({ width, height, cornerRadius: radius, cornerSmoothing: 1 });
  const svg = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><path d="${d}" fill="#fff"/></svg>`,
  ).toString("base64");
  return {
    maskImage: `url("data:image/svg+xml;base64,${svg}")`,
    maskSize: `${width}px ${height}px`,
    maskRepeat: "no-repeat",
  } as any;
}

function wrapTask(task: string, maximumLineLength = 21): string[] {
  const lines: string[] = [];
  for (const word of task.split(/\s+/)) {
    const current = lines.at(-1);
    if (!current || current.length + word.length + 1 > maximumLineLength) {
      lines.push(word);
    } else {
      lines[lines.length - 1] = `${current} ${word}`;
    }
  }
  return lines;
}

function taskCell(label: string, complete: boolean) {
  const lines = wrapTask(label);
  const fontSize = lines.length <= 3 ? 17 : lines.length === 4 ? 15.5 : 14;
  return container({
    style: {
      ...squircle(CELL_WIDTH, CELL_HEIGHT, 20),
      width: CELL_WIDTH,
      height: CELL_HEIGHT,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      paddingLeft: 15,
      paddingRight: 15,
      backgroundImage: complete
        ? "linear-gradient(145deg, #355b3d, #27462f)"
        : `linear-gradient(145deg, #292019, ${PAPER_2})`,
      borderWidth: complete ? 3 : 1,
      borderColor: complete ? COMPLETE : LINE,
    } as any,
    children: [text(lines.join("\n"), {
      width: CELL_WIDTH - 30,
      fontFamily: SANS,
      fontSize,
      fontWeight: 600,
      lineHeight: 1.34,
      textAlign: "center",
      whiteSpace: "pre-wrap",
      color: FOREGROUND,
    })],
  });
}

function formatDateRange(card: BingoCardRecord): string {
  const formatter = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/London",
    weekday: "long",
    day: "numeric",
    month: "long",
  });
  const start = formatter.format(new Date(card.starts_at * 1_000));
  const end = formatter.format(new Date((card.ends_at - 1) * 1_000));
  return `${start} — ${end}`.toUpperCase();
}

export async function generateBingoCardImage(
  card: BingoCardRecord,
  completedTaskIds: Iterable<string> = [],
  playerName?: string | null,
): Promise<Buffer> {
  if (card.tasks.length !== 16) {
    throw new Error(`Bingo #${card.number} must contain exactly 16 tasks`);
  }
  const completed = new Set(completedTaskIds);
  const completedCount = card.tasks.filter((task) => completed.has(task.id)).length;
  const rows = Array.from({ length: 4 }, (_, row) => container({
    style: { display: "flex", flexDirection: "row", gap: GRID_GAP },
    children: card.tasks
      .slice(row * 4, row * 4 + 4)
      .map((task) => taskCell(task.label, completed.has(task.id))),
  }));

  const header = container({
    style: {
      ...squircle(GRID_WIDTH, 170, 36),
      position: "relative",
      width: GRID_WIDTH,
      height: 170,
      backgroundImage: `linear-gradient(135deg, ${ORANGE_FROM}, ${ORANGE_TO})`,
    } as any,
    children: [
      container({
        style: {
          position: "absolute", left: 48, top: 0, height: 170,
          display: "flex", flexDirection: "column", justifyContent: "center", gap: 7,
        } as any,
        children: [
          text(playerName ? `${playerName.toUpperCase()}'S CARD` : "WEEKLY CARD", {
            fontFamily: SANS, fontSize: 13, fontWeight: 700,
            letterSpacing: 1.4, color: "rgb(255 255 255 / 0.7)",
          }),
          text(`BINGO #${card.number}`, {
            fontFamily: SANS, fontSize: 46, fontWeight: 700, color: "#ffffff",
          }),
        ],
      }),
      container({
        style: {
          position: "absolute", right: 34, top: 0, height: 170,
          display: "flex", flexDirection: "column", alignItems: "flex-end",
          justifyContent: "center", gap: 1,
        } as any,
        children: [
          text(`${completedCount} / 16`, {
            fontFamily: SANS, fontSize: 45, color: "#ffffff",
          }),
          text("COMPLETE", {
            fontFamily: SANS, fontSize: 11, fontWeight: 700,
            letterSpacing: 1.6, color: "rgb(255 255 255 / 0.62)",
          }),
        ],
      }),
    ],
  });

  const grid = container({
    style: {
      width: GRID_WIDTH, display: "flex", flexDirection: "column",
      gap: GRID_GAP, marginTop: 18,
    },
    children: rows,
  });

  const footer = container({
    style: {
      ...squircle(GRID_WIDTH, 56, 18),
      position: "relative", width: GRID_WIDTH, height: 56,
      marginTop: 14, backgroundColor: PAPER_2,
    } as any,
    children: [
      container({
        style: {
          position: "absolute", left: 18, top: 0, height: 56,
          display: "flex", flexDirection: "row", alignItems: "center", gap: 8,
        } as any,
        children: [
          image({ src: logoUri, width: 32, height: 32 }),
          text("CRABCRAFT BINGO", {
            fontFamily: SANS, fontSize: 10, fontWeight: 700,
            letterSpacing: 0.7, color: FOREGROUND,
          }),
        ],
      }),
      text(formatDateRange(card), {
        position: "absolute", left: 0, top: 19, width: GRID_WIDTH,
        fontFamily: SANS, fontSize: 13, fontWeight: 600,
        letterSpacing: 0.5, textAlign: "center", color: FOREGROUND,
      } as any),
      text("/bingo", {
        position: "absolute", right: 18, top: 18,
        fontFamily: SANS, fontSize: 18, color: ORANGE_TO,
      } as any),
    ],
  });

  const root = container({
    style: {
      width: WIDTH, height: HEIGHT, display: "flex", flexDirection: "column",
      alignItems: "center", paddingTop: 34, paddingBottom: 28,
      backgroundColor: PAPER,
    },
    children: [header, grid, footer],
  });
  const options: NonNullable<Parameters<Renderer["render"]>[1]>
    & { fonts: FontLoader[] } = {
      format: "png",
      devicePixelRatio: 2,
      fonts,
    };
  return Buffer.from(await renderer.render(root, options));
}
