import { describe, expect, test } from "bun:test";
import {
  generatePlayerListImage,
  parseMinecraftText,
  type OnlinePlayers,
} from "../src/utils/playerListView.js";

describe("generatePlayerListImage", () => {
  test("generates a tablist png", async () => {
    const image = await generatePlayerListImage(
      {
        count: 1,
        players: [player("Steve", "survival")],
      },
      { fetchAvatars: false },
    );

    expect(image.subarray(0, 8)).toEqual(
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    );
  });
});

test("parses legacy nickname formatting without rendering format codes", () => {
  expect(parseMinecraftText("§x§3§c§8§f§3§6§l§o§n§mB")).toEqual([
    {
      text: "B",
      color: "#3c8f36",
      bold: true,
      underline: true,
      strikethrough: true,
      italic: true,
    },
  ]);
});

function player(username: string, server: string) {
  return {
    username,
    uuid: `${username}-uuid`,
    nickname: null,
    nickname_raw: null,
    ping: 42,
    server,
    current_streak: 0,
  };
}
