import { describe, expect, mock, test } from "bun:test";
import {
  galleryHref,
  normaliseGalleryTagKey,
  parseGalleryPlayerParam,
  parseGalleryTagParam,
} from "../src/data/gallery";
import { parseGalleryMediaOrigin } from "../gallery-media-origin";

process.env.GALLERY_MEDIA_BASE_URL = "https://cdn.crabcraft.net";
mock.module("server-only", () => ({}));

const { galleryMediaUrl } = await import("../src/data/gallery-media");

const ORIGINAL_MEDIA_URL =
  "https://cdn.crabcraft.net/gallery/season-7/123/456.webp";

describe("Gallery tag filters", () => {
  test("uses one case-insensitive logical key across channel-specific tags", () => {
    expect(normaliseGalleryTagKey("Build")).toBe("build");
    expect(normaliseGalleryTagKey("  BUILD  ")).toBe("build");
    expect(normaliseGalleryTagKey("Build Ideas")).toBe("build ideas");
  });

  test("accepts tag names in URLs and rejects malformed values", () => {
    expect(parseGalleryTagParam("  Build Ideas  ")).toBe("Build Ideas");
    expect(parseGalleryTagParam("   ")).toBeNull();
    expect(parseGalleryTagParam("Build\u0000Ideas")).toBeNull();
    expect(parseGalleryTagParam("x".repeat(101))).toBeNull();
  });

  test("encodes logical names and preserves pagination filters", () => {
    expect(
      galleryHref({
        season: 7,
        tag: "build ideas",
        player: "Max Moon",
        page: 2,
      }),
    ).toBe(
      "/gallery?season=7&tag=build+ideas&player=Max+Moon&page=2",
    );
    expect(galleryHref({ season: null, tag: null })).toBe("/gallery");
  });

  test("accepts player searches and rejects malformed values", () => {
    expect(parseGalleryPlayerParam("  MaxMoon  ")).toBe("MaxMoon");
    expect(parseGalleryPlayerParam("   ")).toBeNull();
    expect(parseGalleryPlayerParam("Max\u0000Moon")).toBeNull();
    expect(parseGalleryPlayerParam("x".repeat(33))).toBeNull();
  });
});

describe("Gallery media transformations", () => {
  test("requires a credential-free HTTPS origin", () => {
    expect(parseGalleryMediaOrigin("https://cdn.crabcraft.net/").origin).toBe(
      "https://cdn.crabcraft.net",
    );

    for (const value of [
      undefined,
      "http://cdn.crabcraft.net",
      "https://user:secret@cdn.crabcraft.net",
      "https://cdn.crabcraft.net/gallery",
      "https://cdn.crabcraft.net?bucket=gallery",
      "https://cdn.crabcraft.net#gallery",
    ]) {
      expect(() => parseGalleryMediaOrigin(value)).toThrow();
    }
  });

  test("creates bounded, purgeable variants for each Gallery surface", () => {
    expect(galleryMediaUrl(ORIGINAL_MEDIA_URL, "listing")).toBe(
      "https://cdn.crabcraft.net/cdn-cgi/image/fit=scale-down,format=auto,metadata=none,onerror=redirect,width=960,anim=false/gallery/season-7/123/456.webp",
    );
    expect(galleryMediaUrl(ORIGINAL_MEDIA_URL, "detail")).toContain(
      "width=1280,anim=false/gallery/season-7/123/456.webp",
    );
    expect(galleryMediaUrl(ORIGINAL_MEDIA_URL, "lightbox")).toBe(
      "https://cdn.crabcraft.net/cdn-cgi/image/fit=scale-down,format=auto,metadata=none,onerror=redirect,width=1920/gallery/season-7/123/456.webp",
    );
  });

  test("does not rewrite URLs outside the exact immutable Gallery key space", () => {
    const unchanged = [
      "https://cdn.discordapp.com/attachments/123/456/image.webp",
      "http://cdn.crabcraft.net/gallery/season-7/123/456.webp",
      "https://cdn.crabcraft.net.evil.example/gallery/season-7/123/456.webp",
      "https://cdn.crabcraft.net/gallery/season-7/123/456.webp?download=1",
      "https://cdn.crabcraft.net/gallery/season-7/123/bad%2Fkey.webp",
      "https://cdn.crabcraft.net/cdn-cgi/image/width=1/gallery/season-7/123/456.webp",
      "not a URL",
    ];

    for (const url of unchanged) {
      expect(galleryMediaUrl(url, "listing")).toBe(url);
    }
  });
});
