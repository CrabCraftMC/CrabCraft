import { describe, expect, test } from "bun:test";
import sharp from "sharp";

const {
  GALLERY_MAX_IMAGE_DIMENSION,
  GALLERY_MAX_IMAGE_PIXELS,
  GALLERY_MAX_ATTACHMENT_BYTES,
  buildGalleryStorageKey,
  getGalleryStorageCacheHitDimensions,
  hasGalleryRasterSignature,
  inferGalleryImageContentType,
  readGalleryResponseBytes,
  validateGalleryDeclaredDimensions,
  validateGalleryDecodedMetadata,
  validateGalleryImageBytes,
  validateGalleryResponseContentType,
  validateGalleryS3Region,
  validateGalleryStorageAttachment,
} = await import("../src/utils/galleryStorageHelpers.js");
const { findGalleryChannelConfig, resolveAppliedGalleryTags } = await import(
  "../src/utils/galleryHelpers.js"
);
const { buildGalleryPostContentHash, buildGalleryTagsHash } = await import(
  "../src/utils/galleryHashes.js"
);
const {
  persistStoredGalleryImages,
  queueGalleryStorageWrite,
  storeGalleryImages,
} = await import(
  "../src/utils/galleryImageUploads.js"
);
const { isUnknownChannelError, isUnknownMessageError } = await import(
  "../src/utils/discordErrors.js"
);
const { parseBackfillArguments } = await import(
  "../src/utils/galleryBackfillArgs.js"
);
const { fetchGalleryStarterMessage } = await import(
  "../src/utils/galleryStarter.js"
);

const baseAttachment = {
  id: "222222222222222222",
  url: "https://cdn.discordapp.com/attachments/a/b/image.png",
  filename: "image.png",
  contentType: "image/png",
  size: 1024,
  width: 1920,
  height: 1080,
};

describe("gallery storage helpers", () => {
  test("requires an explicit provider region rather than auto", () => {
    expect(validateGalleryS3Region(" eu-central-003 ")).toBe("eu-central-003");
    expect(() => validateGalleryS3Region("")).toThrow("non-empty region");
    expect(() => validateGalleryS3Region("auto")).toThrow("not auto");
    expect(() => validateGalleryS3Region("EU-Central-003")).toThrow(
      "region reported by the storage provider",
    );
  });

  test("normalises supported content types and filename fallbacks", () => {
    expect(inferGalleryImageContentType("image/jpeg; charset=binary", "x.bin"))
      .toBe("image/jpeg");
    expect(inferGalleryImageContentType(null, "castle.WEBP")).toBe("image/webp");
    expect(inferGalleryImageContentType("image/svg+xml", "unsafe.svg")).toBeNull();
  });

  test("does not override explicit unsupported or contradictory MIME types", () => {
    expect(
      inferGalleryImageContentType("application/octet-stream", "image.png"),
    ).toBeNull();
    expect(inferGalleryImageContentType("image/jpeg", "image.png")).toBeNull();
    expect(inferGalleryImageContentType(null, "image.png")).toBe("image/png");
  });

  test("builds immutable season and Discord-ID based keys", () => {
    expect(
      buildGalleryStorageKey(
        "7",
        "111111111111111111",
        "222222222222222222",
        "image/png",
      ),
    ).toBe(
      "gallery/validated-v1/season-7/111111111111111111/222222222222222222.png",
    );
  });

  test("rejects empty and oversized attachments before download", () => {
    expect(() =>
      validateGalleryStorageAttachment({ ...baseAttachment, size: 0 }),
    ).toThrow("invalid size");
    expect(() =>
      validateGalleryStorageAttachment({
        ...baseAttachment,
        size: GALLERY_MAX_ATTACHMENT_BYTES + 1,
      }),
    ).toThrow("50 MiB");
  });

  test("accepts a supported attachment at the size limit", () => {
    expect(
      validateGalleryStorageAttachment({
        ...baseAttachment,
        size: GALLERY_MAX_ATTACHMENT_BYTES,
      }),
    ).toBe("image/png");
  });

  test("short-circuits existing objects only with usable Discord dimensions", () => {
    expect(
      getGalleryStorageCacheHitDimensions(
        { size: 1_024, contentType: "image/png" },
        1_024,
        "image/png",
        1_920,
        1_080,
      ),
    ).toEqual({
      width: 1920,
      height: 1080,
    });
    expect(
      getGalleryStorageCacheHitDimensions(
        { size: 999, contentType: "image/png" },
        1_024,
        "image/png",
        1_920,
        1_080,
      ),
    ).toBeNull();
    expect(
      getGalleryStorageCacheHitDimensions(
        { size: 1_024, contentType: "image/jpeg" },
        1_024,
        "image/png",
        1_920,
        1_080,
      ),
    ).toBeNull();
    expect(
      getGalleryStorageCacheHitDimensions(
        { size: 1_024, contentType: "image/png" },
        1_024,
        "image/png",
        null,
        null,
      ),
    ).toBeNull();
    expect(
      getGalleryStorageCacheHitDimensions(
        { size: 1_024, contentType: "image/png" },
        1_024,
        "image/png",
        GALLERY_MAX_IMAGE_DIMENSION + 1,
        1,
      ),
    ).toBeNull();
  });

  test("validates response MIME and raster signatures", async () => {
    const png = Uint8Array.from([
      0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    ]);
    expect(hasGalleryRasterSignature(png, "image/png")).toBe(true);
    expect(hasGalleryRasterSignature(png, "image/jpeg")).toBe(false);
    expect(() =>
      validateGalleryResponseContentType(
        baseAttachment.id,
        "IMAGE/PNG; charset=binary",
        "image/png",
      ),
    ).not.toThrow();
    expect(() =>
      validateGalleryResponseContentType(
        baseAttachment.id,
        "text/html; charset=utf-8",
        "image/png",
      ),
    ).toThrow("expected image/png");
    await expect(
      validateGalleryImageBytes(
        baseAttachment.id,
        Uint8Array.from([0xff, 0xd8, 0xff]),
        3,
        "image/png",
      ),
    ).rejects.toThrow("valid image/png signature");

    await expect(
      validateGalleryImageBytes(
        baseAttachment.id,
        png,
        png.byteLength,
        "image/png",
      ),
    ).rejects.toThrow("could not be decoded");
  });

  test("decodes valid image bytes before accepting them", async () => {
    const png = await sharp({
      create: {
        width: 2,
        height: 3,
        channels: 4,
        background: { r: 1, g: 2, b: 3, alpha: 1 },
      },
    })
      .png()
      .toBuffer();

    await expect(
      validateGalleryImageBytes(
        baseAttachment.id,
        png,
        png.byteLength,
        "image/png",
      ),
    ).resolves.toEqual({ width: 2, height: 3 });
  });

  test("rejects an image whose headers parse but pixel data is truncated", async () => {
    const png = await sharp({
      create: {
        width: 100,
        height: 100,
        channels: 4,
        background: { r: 1, g: 2, b: 3, alpha: 1 },
      },
    })
      .png()
      .toBuffer();
    const truncated = png.subarray(0, png.byteLength - 16);

    await expect(
      validateGalleryImageBytes(
        baseAttachment.id,
        truncated,
        truncated.byteLength,
        "image/png",
      ),
    ).rejects.toThrow("fully decoded");
  });

  test("maps Sharp's HEIF decoder metadata back to AVIF", async () => {
    const avif = await sharp({
      create: {
        width: 2,
        height: 3,
        channels: 4,
        background: { r: 1, g: 2, b: 3, alpha: 1 },
      },
    })
      .avif()
      .toBuffer();

    await expect(
      validateGalleryImageBytes(
        baseAttachment.id,
        avif,
        avif.byteLength,
        "image/avif",
      ),
    ).resolves.toEqual({ width: 2, height: 3 });
  });

  test("enforces decoded format, dimension and pixel limits", () => {
    expect(() =>
      validateGalleryDecodedMetadata(
        baseAttachment.id,
        {
          format: "jpeg",
          mediaType: "image/jpeg",
          width: 10,
          height: 10,
        },
        "image/png",
      ),
    ).toThrow("decoded as jpeg");
    expect(() =>
      validateGalleryDecodedMetadata(
        baseAttachment.id,
        {
          format: "png",
          mediaType: "image/png",
          width: GALLERY_MAX_IMAGE_DIMENSION + 1,
          height: 1,
        },
        "image/png",
      ),
    ).toThrow("dimension limit");
    expect(() =>
      validateGalleryDecodedMetadata(
        baseAttachment.id,
        {
          format: "png",
          mediaType: "image/png",
          width: 10_000,
          height: GALLERY_MAX_IMAGE_PIXELS / 10_000 + 1,
        },
        "image/png",
      ),
    ).toThrow("image limit");
    expect(() =>
      validateGalleryDecodedMetadata(
        baseAttachment.id,
        {
          format: "gif",
          mediaType: "image/gif",
          width: 1_000,
          height: 50_000,
          pageHeight: 1_000,
          pages: 41,
        },
        "image/gif",
      ),
    ).toThrow("image limit");
    expect(
      validateGalleryDecodedMetadata(
        baseAttachment.id,
        {
          format: "webp",
          mediaType: "image/webp",
          width: 200,
          height: 1_000,
          pageHeight: 100,
          pages: 10,
        },
        "image/webp",
      ),
    ).toEqual({ width: 200, height: 100 });
  });

  test("uses orientation-aware decoded dimensions and checks Discord metadata", () => {
    const decoded = validateGalleryDecodedMetadata(
      baseAttachment.id,
      {
        format: "jpeg",
        mediaType: "image/jpeg",
        width: 3,
        height: 2,
        autoOrient: { width: 2, height: 3 },
      },
      "image/jpeg",
    );
    expect(decoded).toEqual({ width: 2, height: 3 });
    expect(() =>
      validateGalleryDeclaredDimensions(
        baseAttachment.id,
        { width: 2, height: 3 },
        decoded,
      ),
    ).not.toThrow();
    expect(() =>
      validateGalleryDeclaredDimensions(
        baseAttachment.id,
        { width: 3, height: 2 },
        decoded,
      ),
    ).toThrow("Discord declared 3x2");
  });

  test("accepts AVIF brands only in the file-type brand table", () => {
    const validAvif = Uint8Array.from([
      0x00, 0x00, 0x00, 0x14,
      0x66, 0x74, 0x79, 0x70,
      0x6d, 0x69, 0x66, 0x31,
      0x00, 0x00, 0x00, 0x00,
      0x61, 0x76, 0x69, 0x66,
    ]);
    const spoofedMinorVersion = Uint8Array.from([
      0x00, 0x00, 0x00, 0x10,
      0x66, 0x74, 0x79, 0x70,
      0x6d, 0x69, 0x66, 0x31,
      0x61, 0x76, 0x69, 0x66,
    ]);
    expect(hasGalleryRasterSignature(validAvif, "image/avif")).toBe(true);
    expect(hasGalleryRasterSignature(spoofedMinorVersion, "image/avif")).toBe(
      false,
    );
  });

  test("enforces the download limit while reading a response stream", async () => {
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(Uint8Array.from([1, 2, 3]));
          controller.enqueue(Uint8Array.from([4, 5, 6]));
          controller.close();
        },
      }),
    );

    await expect(
      readGalleryResponseBytes(baseAttachment.id, response, 5, 5),
    ).rejects.toThrow("exceeded its 5-byte limit");
  });

  test("rejects a response whose byte length differs from Discord metadata", async () => {
    const response = new Response(Uint8Array.from([1, 2, 3]), {
      headers: { "content-length": "3" },
    });
    await expect(
      readGalleryResponseBytes(baseAttachment.id, response, 4),
    ).rejects.toThrow("returned 3 bytes; expected 4");
  });
});

describe("gallery channel and tag mapping", () => {
  test("resolves only configured Discord parents", () => {
    const channels = [{ channelId: "111111111111111111", seasonId: "7" }];
    expect(findGalleryChannelConfig(channels, "111111111111111111")).toEqual({
      channelId: "111111111111111111",
      seasonId: "7",
    });
    expect(
      findGalleryChannelConfig(channels, "999999999999999999"),
    ).toBeNull();
  });

  test("preserves unknown applied tags with valid deterministic positions", () => {
    const available = [
      {
        discordTagId: "tag-known",
        name: "Build",
        emojiId: null,
        emojiName: "🏠",
        position: 0,
        moderated: false,
      },
    ];
    expect(
      resolveAppliedGalleryTags(
        ["tag-known", "tag-old-a", "tag-old-b"],
        available,
      ),
    ).toEqual([
      available[0],
      {
        discordTagId: "tag-old-a",
        name: "Unknown tag",
        emojiId: null,
        emojiName: null,
        position: 2,
        moderated: false,
      },
      {
        discordTagId: "tag-old-b",
        name: "Unknown tag",
        emojiId: null,
        emojiName: null,
        position: 3,
        moderated: false,
      },
    ]);
  });
});

describe("gallery Discord errors", () => {
  test("distinguishes an authoritative deleted starter from transient errors", () => {
    expect(isUnknownMessageError({ code: 10_008 })).toBe(true);
    expect(isUnknownMessageError({ code: 50_013 })).toBe(false);
    expect(isUnknownMessageError(new Error("network failed"))).toBe(false);
  });

  test("distinguishes an authoritative deleted channel from access failures", () => {
    expect(isUnknownChannelError({ code: 10_003 })).toBe(true);
    expect(isUnknownChannelError({ code: 50_001 })).toBe(false);
    expect(isUnknownChannelError(new Error("network failed"))).toBe(false);
  });

  test("retries a newly created starter after a transient Unknown Message", async () => {
    let attempts = 0;
    const starter = { id: "starter" };
    const thread = {
      id: "thread",
      async fetchStarterMessage() {
        attempts += 1;
        if (attempts === 1) throw { code: 10_008 };
        return starter;
      },
    };

    await expect(
      fetchGalleryStarterMessage(thread as never, true, [0, 0]),
    ).resolves.toBe(starter as never);
    expect(attempts).toBe(2);
  });

  test("treats Unknown Message as authoritative without creation retries", async () => {
    let attempts = 0;
    const thread = {
      id: "thread",
      async fetchStarterMessage() {
        attempts += 1;
        throw { code: 10_008 };
      },
    };

    await expect(
      fetchGalleryStarterMessage(thread as never, false, [0, 0]),
    ).resolves.toBeNull();
    expect(attempts).toBe(1);
  });
});

describe("gallery content hashes", () => {
  const tag = {
    discordTagId: "tag-build",
    name: "Build",
    emojiId: null,
    emojiName: "🏠",
    position: 0,
    moderated: false,
  };
  const image = {
    discordAttachmentId: "222222222222222222",
    position: 0,
    storageKey: "gallery/season-7/post/image.png",
    publicUrl: "https://gallery.example/image.png",
    filename: "image.png",
    alt: "A castle",
    contentType: "image/png",
    width: 1920,
    height: 1080,
    size: 1024,
  };
  const snapshot = {
    seasonId: "7",
    title: "Castle",
    content: "Built at spawn",
    authorDiscordId: "111111111111111111",
    authorDiscordUsername: "builder",
    authorDisplayName: "Builder",
    postedAt: 1_700_000_000,
    tags: [tag],
    images: [image],
  };

  test("changes when rendered post content changes", () => {
    expect(buildGalleryPostContentHash(snapshot)).not.toBe(
      buildGalleryPostContentHash({ ...snapshot, title: "New castle" }),
    );
    expect(buildGalleryPostContentHash(snapshot)).not.toBe(
      buildGalleryPostContentHash({
        ...snapshot,
        images: [{ ...image, alt: "A larger castle" }],
      }),
    );
  });

  test("catalogue hashes ignore non-rendered moderation state", () => {
    expect(buildGalleryTagsHash([tag])).toBe(
      buildGalleryTagsHash([{ ...tag, moderated: true }]),
    );
    expect(buildGalleryTagsHash([tag])).not.toBe(
      buildGalleryTagsHash([{ ...tag, name: "Architecture" }]),
    );
  });
});

describe("gallery multi-image uploads", () => {
  test("serialises storage across concurrent thread syncs", async () => {
    let active = 0;
    let peak = 0;
    const attachment = (id: string) => ({
      id,
      url: `https://cdn.discordapp.com/attachments/a/b/${id}.png`,
      name: `${id}.png`,
      title: null,
      description: null,
      contentType: "image/png",
      size: 1,
      width: 1,
      height: 1,
    });
    const storage = {
      getPublicUrl(storageKey: string) {
        return `https://gallery.example/${storageKey}`;
      },
      async store(_seasonId: string, _threadId: string, image: { id: string }) {
        active += 1;
        peak = Math.max(peak, active);
        await new Promise((resolve) => setTimeout(resolve, 5));
        active -= 1;
        return {
          storageKey: `gallery/${image.id}.png`,
          publicUrl: `https://gallery.example/${image.id}.png`,
          width: 1,
          height: 1,
        };
      },
      async delete() {},
    };

    await Promise.all([
      queueGalleryStorageWrite(() =>
        storeGalleryImages(
          storage,
          "7",
          "thread-a",
          [attachment("first")],
          1,
        ),
      ),
      queueGalleryStorageWrite(() =>
        storeGalleryImages(
          storage,
          "7",
          "thread-b",
          [attachment("second")],
          1,
        ),
      ),
    ]);
    expect(peak).toBe(1);
  });

  test("queues completed images when a later upload fails", async () => {
    const uploaded: string[] = [];
    const queued: Array<{
      images: Array<{ storageKey: string; publicUrl: string }>;
      queuedAt: number;
    }> = [];
    const attachment = (id: string) => ({
      id,
      url: `https://cdn.discordapp.com/attachments/a/b/${id}.png`,
      name: `${id}.png`,
      title: null,
      description: null,
      contentType: "image/png",
      size: 1_024,
      width: 1_920,
      height: 1_080,
    });

    await expect(
      storeGalleryImages(
        {
          getPublicUrl(storageKey) {
            return `https://gallery.example/${storageKey}`;
          },
          async store(_seasonId, _threadId, image) {
            uploaded.push(image.id);
            if (image.id === "second") throw new Error("upload failed");
            return {
              storageKey: `gallery/${image.id}.png`,
              publicUrl: `https://gallery.example/${image.id}.png`,
              width: 1_920,
              height: 1_080,
            };
          },
          async delete() {},
        },
        "7",
        "thread",
        [attachment("first"), attachment("second"), attachment("third")],
        123,
        async (images, queuedAt) => {
          queued.push({ images, queuedAt });
          return images.length;
        },
      ),
    ).rejects.toThrow("upload failed");

    expect(uploaded).toEqual(["first", "second"]);
    expect(queued).toEqual([
      {
        images: [
          {
            storageKey: "gallery/first.png",
            publicUrl: "https://gallery.example/first.png",
          },
        ],
        queuedAt: 123,
      },
    ]);
  });

  test("queues all stored images when persistence fails and preserves the error", async () => {
    const persistenceError = new Error("database unavailable");
    const queued: Array<{
      images: Array<{ storageKey: string; publicUrl: string }>;
      queuedAt: number;
    }> = [];
    const images = [
      {
        discordAttachmentId: "first",
        position: 0,
        storageKey: "gallery/first.png",
        publicUrl: "https://gallery.example/first.png",
        filename: "first.png",
        alt: null,
        contentType: "image/png" as const,
        width: 1_920,
        height: 1_080,
        size: 1_024,
      },
    ];

    await expect(
      persistStoredGalleryImages(
        images,
        456,
        "thread",
        async () => {
          throw persistenceError;
        },
        async (cleanupImages, queuedAt) => {
          queued.push({ images: cleanupImages, queuedAt });
          return cleanupImages.length;
        },
      ),
    ).rejects.toBe(persistenceError);
    expect(queued).toEqual([
      {
        images: [
          {
            storageKey: "gallery/first.png",
            publicUrl: "https://gallery.example/first.png",
          },
        ],
        queuedAt: 456,
      },
    ]);
  });

  test("preserves the persistence error if cleanup queueing also fails", async () => {
    const persistenceError = new Error("database unavailable");
    const image = {
      discordAttachmentId: "first",
      position: 0,
      storageKey: "gallery/first.png",
      publicUrl: "https://gallery.example/first.png",
      filename: "first.png",
      alt: null,
      contentType: "image/png" as const,
      width: 1,
      height: 1,
      size: 1,
    };

    await expect(
      persistStoredGalleryImages(
        [image],
        456,
        "thread",
        async () => {
          throw persistenceError;
        },
        async () => {
          throw new Error("cleanup queue unavailable");
        },
      ),
    ).rejects.toBe(persistenceError);
  });
});

describe("gallery backfill arguments", () => {
  test("supports repeatable season selection and dry runs", () => {
    const parsed = parseBackfillArguments([
      "--dry-run",
      "--season",
      "6",
      "--season=7",
    ]);
    expect(parsed.dryRun).toBe(true);
    expect([...parsed.seasonIds]).toEqual(["6", "7"]);
  });

  test("rejects invalid seasons", () => {
    expect(() => parseBackfillArguments(["--season", "8"])).toThrow(
      "from 1 to 7",
    );
  });
});
