import { S3Client } from "bun";
import config from "./config.js";
import type { GalleryStoredImage } from "./galleryTypes.js";
import {
  bindGalleryPurgeUrl,
  buildGalleryPublicUrl,
  parseGalleryCloudflarePurgeConfig,
  purgeGalleryFileFromCloudflare,
  type GalleryCloudflarePurgeConfig,
} from "./galleryPurge.js";
import {
  buildGalleryStorageKey,
  getGalleryAttachmentDimensions,
  getGalleryStorageCacheHitDimensions,
  readGalleryResponseBytes,
  validateGalleryDeclaredDimensions,
  validateGalleryStorageAttachment,
  validateGalleryImageBytes,
  validateGalleryResponseContentType,
  validateGalleryS3Region,
  type GalleryStorageAttachment,
} from "./galleryStorageHelpers.js";

export interface GalleryStorage {
  getPublicUrl(storageKey: string): string;
  store(
    seasonId: string,
    threadId: string,
    attachment: GalleryStorageAttachment,
  ): Promise<GalleryStoredImage>;
  delete(storageKey: string, publicUrl: string): Promise<void>;
}

function normaliseBaseUrl(value: string): string {
  const url = new URL(value);
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw new Error(
      "GALLERY_MEDIA_BASE_URL must be a credential-free HTTPS origin without a path, query or fragment.",
    );
  }
  return `${url.toString().replace(/\/+$/, "")}/`;
}

class S3GalleryStorage implements GalleryStorage {
  private readonly client: S3Client;
  private readonly publicBaseUrl: string;
  private readonly cloudflarePurge: GalleryCloudflarePurgeConfig;

  constructor() {
    const required = {
      GALLERY_S3_ENDPOINT: config.GALLERY_S3_ENDPOINT,
      GALLERY_S3_ACCESS_KEY_ID: config.GALLERY_S3_ACCESS_KEY_ID,
      GALLERY_S3_SECRET_ACCESS_KEY: config.GALLERY_S3_SECRET_ACCESS_KEY,
      GALLERY_S3_BUCKET: config.GALLERY_S3_BUCKET,
      GALLERY_S3_REGION: config.GALLERY_S3_REGION,
      GALLERY_MEDIA_BASE_URL: config.GALLERY_MEDIA_BASE_URL,
    };
    const missing = Object.entries(required)
      .filter(([, value]) => !value.trim())
      .map(([name]) => name);
    if (missing.length > 0) {
      throw new Error(
        `Gallery storage is not configured; missing ${missing.join(", ")}.`,
      );
    }

    const endpoint = new URL(config.GALLERY_S3_ENDPOINT);
    if (
      endpoint.protocol !== "https:" ||
      endpoint.username ||
      endpoint.password ||
      endpoint.search ||
      endpoint.hash
    ) {
      throw new Error(
        "GALLERY_S3_ENDPOINT must be a credential-free HTTPS URL without a query or fragment.",
      );
    }

    const region = validateGalleryS3Region(config.GALLERY_S3_REGION);

    this.publicBaseUrl = normaliseBaseUrl(config.GALLERY_MEDIA_BASE_URL);
    this.cloudflarePurge = parseGalleryCloudflarePurgeConfig(
      config.GALLERY_CLOUDFLARE_ZONE_ID,
      config.GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN,
      true,
    );
    this.client = new S3Client({
      endpoint: endpoint.toString().replace(/\/+$/, ""),
      accessKeyId: config.GALLERY_S3_ACCESS_KEY_ID,
      secretAccessKey: config.GALLERY_S3_SECRET_ACCESS_KEY,
      bucket: config.GALLERY_S3_BUCKET,
      region,
    });
  }

  getPublicUrl(storageKey: string): string {
    return buildGalleryPublicUrl(this.publicBaseUrl, storageKey);
  }

  async store(
    seasonId: string,
    threadId: string,
    attachment: GalleryStorageAttachment,
  ): Promise<GalleryStoredImage> {
    const contentType = validateGalleryStorageAttachment(attachment);
    const declaredDimensions = getGalleryAttachmentDimensions(
      attachment.width,
      attachment.height,
    );
    const storageKey = buildGalleryStorageKey(
      seasonId,
      threadId,
      attachment.id,
      contentType,
    );

    const source = new URL(attachment.url);
    if (
      source.protocol !== "https:" ||
      !["cdn.discordapp.com", "media.discordapp.net"].includes(source.hostname)
    ) {
      throw new Error(`Attachment ${attachment.id} has an unexpected source URL.`);
    }

    const alreadyStored = await this.client.exists(storageKey);
    const storedMetadata = alreadyStored
      ? await this.client.stat(storageKey)
      : null;
    const cachedDimensions = getGalleryStorageCacheHitDimensions(
      storedMetadata
        ? { size: storedMetadata.size, contentType: storedMetadata.type }
        : null,
      attachment.size,
      contentType,
      attachment.width,
      attachment.height,
    );
    if (cachedDimensions) {
      return {
        storageKey,
        publicUrl: this.getPublicUrl(storageKey),
        ...cachedDimensions,
      };
    }

    const response = await fetch(source, {
      redirect: "error",
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) {
      throw new Error(
        `Discord returned ${response.status} for attachment ${attachment.id}.`,
      );
    }

    validateGalleryResponseContentType(
      attachment.id,
      response.headers.get("content-type"),
      contentType,
    );
    const bytes = await readGalleryResponseBytes(
      attachment.id,
      response,
      attachment.size,
    );
    const decodedDimensions = await validateGalleryImageBytes(
      attachment.id,
      bytes,
      attachment.size,
      contentType,
    );
    validateGalleryDeclaredDimensions(
      attachment.id,
      declaredDimensions,
      decodedDimensions,
    );
    // Reaching this path means the key was absent or failed cache metadata /
    // dimension validation. Always overwrite it with the fully decoded source.
    await this.client.write(storageKey, bytes, { type: contentType });

    return {
      storageKey,
      publicUrl: this.getPublicUrl(storageKey),
      ...decodedDimensions,
    };
  }

  async delete(storageKey: string, publicUrl: string): Promise<void> {
    const purgeUrl = bindGalleryPurgeUrl(
      this.getPublicUrl(storageKey),
      publicUrl,
    );
    await this.client.delete(storageKey);
    await purgeGalleryFileFromCloudflare(this.cloudflarePurge, purgeUrl);
  }
}

let storage: GalleryStorage | undefined;

export function getGalleryStorage(): GalleryStorage {
  storage ??= new S3GalleryStorage();
  return storage;
}
