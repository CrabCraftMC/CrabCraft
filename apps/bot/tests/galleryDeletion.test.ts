import { describe, expect, test } from "bun:test";
import { processDueGalleryStorageDeletions } from "../src/utils/galleryDeletionProcessor.js";
import {
  bindGalleryPurgeUrl,
  buildGalleryPublicUrl,
  parseGalleryCloudflarePurgeConfig,
  purgeGalleryFileFromCloudflare,
  validateCloudflarePurgeResponse,
  validateGalleryPurgeUrl,
} from "../src/utils/galleryPurge.js";

describe("gallery Cloudflare purge helpers", () => {
  test("requires paired credentials and supports an S3-required mode", () => {
    expect(parseGalleryCloudflarePurgeConfig(undefined, undefined)).toBeNull();
    expect(() =>
      parseGalleryCloudflarePurgeConfig(undefined, undefined, true),
    ).toThrow("Gallery S3 storage requires");
    expect(parseGalleryCloudflarePurgeConfig(" zone ", " token ")).toEqual({
      zoneId: "zone",
      token: "token",
    });
    expect(() => parseGalleryCloudflarePurgeConfig("zone", undefined)).toThrow(
      "must be configured together",
    );
    expect(() => parseGalleryCloudflarePurgeConfig(undefined, "token")).toThrow(
      "must be configured together",
    );
  });

  test("builds only same-origin HTTPS URLs for durable storage reservations", () => {
    expect(
      buildGalleryPublicUrl(
        "https://cdn.crabcraft.net",
        "gallery/validated-v1/season-7/thread/image.png",
      ),
    ).toBe(
      "https://cdn.crabcraft.net/gallery/validated-v1/season-7/thread/image.png",
    );
    expect(() =>
      buildGalleryPublicUrl(
        "https://cdn.crabcraft.net",
        "https://example.com/image.png",
      ),
    ).toThrow("Invalid gallery storage key");
    expect(() =>
      buildGalleryPublicUrl(
        "http://cdn.crabcraft.net",
        "gallery/season-7/thread/image.png",
      ),
    ).toThrow("credential-free HTTPS");
    expect(() =>
      buildGalleryPublicUrl(
        "https://user:secret@cdn.crabcraft.net",
        "gallery/season-7/thread/image.png",
      ),
    ).toThrow("credential-free HTTPS");
    expect(() =>
      buildGalleryPublicUrl(
        "https://cdn.crabcraft.net/media",
        "gallery/season-7/thread/image.png",
      ),
    ).toThrow("HTTPS origin without a path");
    expect(() => validateGalleryPurgeUrl("http://old.example/image.png")).toThrow(
      "credential-free HTTPS",
    );
    expect(validateGalleryPurgeUrl("https://old.example/image.png")).toBe(
      "https://old.example/image.png",
    );
  });

  test("binds deletion purges to the canonical URL for the storage key", () => {
    const canonical =
      "https://cdn.crabcraft.net/gallery/validated-v1/season-7/thread/image.png";
    expect(bindGalleryPurgeUrl(canonical, canonical)).toBe(canonical);
    expect(() =>
      bindGalleryPurgeUrl(canonical, "https://other.example/image.png"),
    ).toThrow("does not match its canonical storage URL");
    expect(() =>
      bindGalleryPurgeUrl(canonical, `${canonical}?purge=everything`),
    ).toThrow("does not match its canonical storage URL");
  });

  test("requires Cloudflare's structured success response", () => {
    expect(() =>
      validateCloudflarePurgeResponse({ success: true, errors: [] }),
    ).not.toThrow();
    expect(() =>
      validateCloudflarePurgeResponse({ success: false, errors: [] }),
    ).toThrow("did not confirm");
    expect(() => validateCloudflarePurgeResponse({ success: true })).toThrow(
      "did not confirm",
    );
  });

  test("sends one HTTPS file purge with a timeout and validates JSON", async () => {
    let requestUrl = "";
    let requestInit: RequestInit | undefined;
    await purgeGalleryFileFromCloudflare(
      { zoneId: "zone/id", token: "secret" },
      "https://cdn.crabcraft.net/gallery/image.png",
      async (input, init) => {
        requestUrl = input.toString();
        requestInit = init;
        return Response.json({ success: true, errors: [] });
      },
    );

    expect(requestUrl).toBe(
      "https://api.cloudflare.com/client/v4/zones/zone%2Fid/purge_cache",
    );
    expect(requestInit?.method).toBe("POST");
    expect(requestInit?.signal).toBeInstanceOf(AbortSignal);
    expect(JSON.parse(String(requestInit?.body))).toEqual({
      files: ["https://cdn.crabcraft.net/gallery/image.png"],
    });
    expect((requestInit?.headers as Record<string, string>).Authorization).toBe(
      "Bearer secret",
    );
  });

  test("rejects non-JSON and unsuccessful HTTP responses", async () => {
    await expect(
      purgeGalleryFileFromCloudflare(
        { zoneId: "zone", token: "token" },
        "https://cdn.crabcraft.net/gallery/image.png",
        async () => new Response("no", { status: 502 }),
      ),
    ).rejects.toThrow("HTTP 502");
    await expect(
      purgeGalleryFileFromCloudflare(
        { zoneId: "zone", token: "token" },
        "https://cdn.crabcraft.net/gallery/image.png",
        async () => new Response("ok", { status: 200 }),
      ),
    ).rejects.toThrow("non-JSON");
  });
});

describe("gallery storage deletion processor", () => {
  test("re-checks leases, completes deletes and records isolated failures", async () => {
    const completed: string[] = [];
    const failed: Array<[string, number, string]> = [];
    const claim = (name: string) => ({
      storageKey: `gallery/${name}.png`,
      publicUrl: `https://old.example/${name}.png`,
      attempts: 1,
      claimedAt: 123,
      leaseUntil: 183,
    });
    const stats = await processDueGalleryStorageDeletions(
      {
        async claim(now, limit, leaseUntil) {
          expect(now).toBe(123);
          expect(limit).toBe(10);
          expect(leaseUntil).toBe(183);
          return [claim("good"), claim("cancelled"), claim("bad")];
        },
        async execute(item, deleteObject) {
          if (item.storageKey.endsWith("cancelled.png")) {
            return "stale-claim" as const;
          }
          await deleteObject();
          completed.push(item.storageKey);
          return "deleted" as const;
        },
        async fail(item, attemptedAt, error) {
          failed.push([item.storageKey, attemptedAt, error]);
          return true;
        },
      },
      {
        async delete(storageKey, publicUrl) {
          expect(publicUrl).toBe(`https://old.example/${storageKey.split("/")[1]}`);
          if (storageKey.endsWith("bad.png")) throw new Error("delete failed");
        },
      },
      { clock: () => 123, limit: 10, leaseSeconds: 60 },
    );

    expect(stats).toEqual({
      attempted: 3,
      completed: 1,
      skipped: 1,
      failed: 1,
    });
    expect(completed).toEqual(["gallery/good.png"]);
    expect(failed).toEqual([
      ["gallery/bad.png", 123, "Error: delete failed"],
    ]);
  });
});
