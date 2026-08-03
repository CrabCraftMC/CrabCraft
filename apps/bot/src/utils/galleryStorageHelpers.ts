import { Writable } from "node:stream";
import { pipeline } from "node:stream/promises";
import sharp from "sharp";

const IMAGE_EXTENSION_BY_CONTENT_TYPE: Readonly<Record<string, string>> = {
  "image/avif": "avif",
  "image/gif": "gif",
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
};

const CONTENT_TYPE_BY_EXTENSION: Readonly<Record<string, string>> = {
  avif: "image/avif",
  gif: "image/gif",
  jpeg: "image/jpeg",
  jpg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
};

export const GALLERY_MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
export const GALLERY_MAX_IMAGE_DIMENSION = 16_384;
export const GALLERY_MAX_IMAGE_PIXELS = 40_000_000;

export function validateGalleryS3Region(value: string): string {
  const region = value.trim();
  if (
    region === "auto" ||
    !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(region)
  ) {
    throw new Error(
      "GALLERY_S3_REGION must be the non-empty region reported by the storage provider, not auto.",
    );
  }
  return region;
}

export interface GalleryValidatedImageDimensions {
  width: number;
  height: number;
}

export interface GalleryStoredObjectMetadata {
  size: number;
  contentType: string;
}

const SHARP_FORMAT_BY_CONTENT_TYPE: Readonly<Record<string, string>> = {
  "image/avif": "heif",
  "image/gif": "gif",
  "image/jpeg": "jpeg",
  "image/png": "png",
  "image/webp": "webp",
};

export interface GalleryStorageAttachment {
  id: string;
  url: string;
  filename: string;
  contentType: string | null;
  size: number;
  width: number | null;
  height: number | null;
}

export function getGalleryAttachmentDimensions(
  width: number | null,
  height: number | null,
): GalleryValidatedImageDimensions | null {
  if (width === null && height === null) return null;
  if (
    !Number.isSafeInteger(width) ||
    !Number.isSafeInteger(height) ||
    !width ||
    !height ||
    width <= 0 ||
    height <= 0
  ) return null;
  if (
    width > GALLERY_MAX_IMAGE_DIMENSION ||
    height > GALLERY_MAX_IMAGE_DIMENSION
  ) return null;
  if (width * height > GALLERY_MAX_IMAGE_PIXELS) return null;
  return { width, height };
}

export function getGalleryStorageCacheHitDimensions(
  stored: GalleryStoredObjectMetadata | null,
  expectedSize: number,
  expectedContentType: string,
  width: number | null,
  height: number | null,
): GalleryValidatedImageDimensions | null {
  const storedContentType =
    stored?.contentType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  if (
    !stored ||
    stored.size !== expectedSize ||
    storedContentType !== expectedContentType
  ) {
    return null;
  }
  return getGalleryAttachmentDimensions(width, height);
}

function contentTypeFromFilename(filename: string): string | null {
  const extension = filename.split(".").pop()?.toLowerCase() ?? "";
  return CONTENT_TYPE_BY_EXTENSION[extension] ?? null;
}

export function inferGalleryImageContentType(
  contentType: string | null,
  filename: string,
): string | null {
  const filenameContentType = contentTypeFromFilename(filename);

  // Discord's explicit MIME type is authoritative. Falling back to an image
  // extension when Discord says otherwise would allow non-images through the
  // attachment filter. A recognised but contradictory extension is rejected
  // for the same reason.
  if (contentType !== null) {
    const normalised = contentType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
    if (!IMAGE_EXTENSION_BY_CONTENT_TYPE[normalised]) return null;
    if (filenameContentType && filenameContentType !== normalised) return null;
    return normalised;
  }

  return filenameContentType;
}

export function buildGalleryStorageKey(
  seasonId: string,
  threadId: string,
  attachmentId: string,
  contentType: string,
): string {
  const extension = IMAGE_EXTENSION_BY_CONTENT_TYPE[contentType];
  if (!extension) throw new Error(`Unsupported gallery image type: ${contentType}`);
  // The versioned namespace is validation provenance: every object under it
  // was written only after the complete decoder checks in this module.
  return `gallery/validated-v1/season-${seasonId}/${threadId}/${attachmentId}.${extension}`;
}

export function validateGalleryStorageAttachment(
  attachment: GalleryStorageAttachment,
): string {
  const contentType = inferGalleryImageContentType(
    attachment.contentType,
    attachment.filename,
  );
  if (!contentType) {
    throw new Error(
      `Attachment ${attachment.id} is not a supported raster image.`,
    );
  }
  if (!Number.isSafeInteger(attachment.size) || attachment.size <= 0) {
    throw new Error(`Attachment ${attachment.id} has an invalid size.`);
  }
  if (attachment.size > GALLERY_MAX_ATTACHMENT_BYTES) {
    throw new Error(
      `Attachment ${attachment.id} exceeds the 50 MiB gallery limit.`,
    );
  }
  return contentType;
}

function hasBytesAt(
  bytes: Uint8Array,
  offset: number,
  expected: readonly number[],
): boolean {
  return expected.every((value, index) => bytes[offset + index] === value);
}

function hasAsciiAt(bytes: Uint8Array, offset: number, expected: string): boolean {
  if (offset + expected.length > bytes.length) return false;
  return [...expected].every(
    (character, index) => bytes[offset + index] === character.charCodeAt(0),
  );
}

function hasAvifSignature(bytes: Uint8Array): boolean {
  if (bytes.length < 16 || !hasAsciiAt(bytes, 4, "ftyp")) return false;

  const boxSize = new DataView(
    bytes.buffer,
    bytes.byteOffset,
    bytes.byteLength,
  ).getUint32(0);
  if (
    boxSize < 16 ||
    boxSize > bytes.length ||
    (boxSize - 16) % 4 !== 0
  ) {
    return false;
  }

  if (hasAsciiAt(bytes, 8, "avif") || hasAsciiAt(bytes, 8, "avis")) {
    return true;
  }
  // Offset 12 is the minor version, not a brand.
  for (let offset = 16; offset + 4 <= boxSize; offset += 4) {
    if (hasAsciiAt(bytes, offset, "avif") || hasAsciiAt(bytes, offset, "avis")) {
      return true;
    }
  }
  return false;
}

export function hasGalleryRasterSignature(
  bytes: Uint8Array,
  contentType: string,
): boolean {
  if (contentType === "image/png") {
    return hasBytesAt(bytes, 0, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  }
  if (contentType === "image/jpeg") {
    return hasBytesAt(bytes, 0, [0xff, 0xd8, 0xff]);
  }
  if (contentType === "image/gif") {
    return hasAsciiAt(bytes, 0, "GIF87a") || hasAsciiAt(bytes, 0, "GIF89a");
  }
  if (contentType === "image/webp") {
    return hasAsciiAt(bytes, 0, "RIFF") && hasAsciiAt(bytes, 8, "WEBP");
  }
  if (contentType === "image/avif") return hasAvifSignature(bytes);
  return false;
}

export function validateGalleryResponseContentType(
  attachmentId: string,
  responseContentType: string | null,
  expectedContentType: string,
): void {
  const normalised =
    responseContentType?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  if (normalised !== expectedContentType) {
    throw new Error(
      `Attachment ${attachmentId} returned ${normalised || "no Content-Type"}; expected ${expectedContentType}.`,
    );
  }
}

async function cancelReader(
  reader: ReadableStreamDefaultReader<Uint8Array>,
): Promise<void> {
  try {
    await reader.cancel();
  } catch {
    // Preserve the validation error that caused cancellation.
  }
}

export async function readGalleryResponseBytes(
  attachmentId: string,
  response: Response,
  expectedSize: number,
  maxBytes = GALLERY_MAX_ATTACHMENT_BYTES,
): Promise<Uint8Array> {
  if (
    !Number.isSafeInteger(expectedSize) ||
    expectedSize <= 0 ||
    expectedSize > maxBytes
  ) {
    throw new Error(`Attachment ${attachmentId} has an invalid expected size.`);
  }

  const contentLengthHeader = response.headers.get("content-length");
  if (contentLengthHeader !== null) {
    const value = contentLengthHeader.trim();
    const contentLength = /^\d+$/.test(value) ? Number(value) : Number.NaN;
    if (!Number.isSafeInteger(contentLength) || contentLength > maxBytes) {
      throw new Error(`Attachment ${attachmentId} has an invalid Content-Length.`);
    }
    if (contentLength !== expectedSize) {
      throw new Error(
        `Attachment ${attachmentId} returned ${contentLength} bytes; expected ${expectedSize}.`,
      );
    }
  }

  if (!response.body) {
    throw new Error(`Attachment ${attachmentId} returned no response body.`);
  }

  const bytes = new Uint8Array(expectedSize);
  const reader = response.body.getReader();
  let offset = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value.length === 0) continue;

      const nextOffset = offset + value.length;
      if (nextOffset > maxBytes || nextOffset > expectedSize) {
        await cancelReader(reader);
        throw new Error(
          `Attachment ${attachmentId} exceeded its ${expectedSize}-byte limit while downloading.`,
        );
      }
      bytes.set(value, offset);
      offset = nextOffset;
    }
  } finally {
    reader.releaseLock();
  }

  if (offset !== expectedSize) {
    throw new Error(
      `Attachment ${attachmentId} returned ${offset} bytes; expected ${expectedSize}.`,
    );
  }
  return bytes;
}

export function validateGalleryDecodedMetadata(
  attachmentId: string,
  metadata: {
    format?: string;
    width?: number;
    height?: number;
    pages?: number;
    pageHeight?: number;
    mediaType?: string;
    compression?: string;
    autoOrient?: { width: number; height: number };
  },
  expectedContentType: string,
): GalleryValidatedImageDimensions {
  const expectedFormat = SHARP_FORMAT_BY_CONTENT_TYPE[expectedContentType];
  if (
    !expectedFormat ||
    metadata.format !== expectedFormat ||
    metadata.mediaType !== expectedContentType ||
    (expectedContentType === "image/avif" && metadata.compression !== "av1")
  ) {
    throw new Error(
      `Attachment ${attachmentId} decoded as ${metadata.format ?? "an unknown format"}; expected ${expectedContentType}.`,
    );
  }

  const pages = metadata.pages ?? 1;
  const width =
    pages === 1 ? metadata.autoOrient?.width ?? metadata.width : metadata.width;
  const height =
    pages === 1
      ? metadata.autoOrient?.height ?? metadata.height
      : metadata.pageHeight ?? metadata.height;
  if (
    !Number.isSafeInteger(width) ||
    !Number.isSafeInteger(height) ||
    !Number.isSafeInteger(pages) ||
    !width ||
    !height ||
    !pages ||
    width <= 0 ||
    height <= 0 ||
    pages <= 0
  ) {
    throw new Error(`Attachment ${attachmentId} has invalid image dimensions.`);
  }
  if (
    width > GALLERY_MAX_IMAGE_DIMENSION ||
    height > GALLERY_MAX_IMAGE_DIMENSION
  ) {
    throw new Error(
      `Attachment ${attachmentId} exceeds the ${GALLERY_MAX_IMAGE_DIMENSION}-pixel dimension limit.`,
    );
  }
  const totalPixels = width * height * pages;
  if (
    !Number.isSafeInteger(totalPixels) ||
    totalPixels > GALLERY_MAX_IMAGE_PIXELS
  ) {
    throw new Error(
      `Attachment ${attachmentId} exceeds the ${GALLERY_MAX_IMAGE_PIXELS}-pixel image limit.`,
    );
  }
  return { width, height };
}

export function validateGalleryDeclaredDimensions(
  attachmentId: string,
  declared: GalleryValidatedImageDimensions | null,
  decoded: GalleryValidatedImageDimensions,
): void {
  if (
    declared &&
    (declared.width !== decoded.width || declared.height !== decoded.height)
  ) {
    throw new Error(
      `Attachment ${attachmentId} decoded as ${decoded.width}x${decoded.height}; Discord declared ${declared.width}x${declared.height}.`,
    );
  }
}

export async function validateGalleryImageBytes(
  attachmentId: string,
  bytes: Uint8Array,
  expectedSize: number,
  expectedContentType: string,
): Promise<GalleryValidatedImageDimensions> {
  if (bytes.byteLength !== expectedSize) {
    throw new Error(
      `Attachment ${attachmentId} returned ${bytes.byteLength} bytes; expected ${expectedSize}.`,
    );
  }
  if (!hasGalleryRasterSignature(bytes, expectedContentType)) {
    throw new Error(
      `Attachment ${attachmentId} does not contain a valid ${expectedContentType} signature.`,
    );
  }

  const sharpOptions = {
    animated: true,
    limitInputPixels: GALLERY_MAX_IMAGE_PIXELS,
    failOn: "warning" as const,
  };
  let metadata: Awaited<ReturnType<ReturnType<typeof sharp>["metadata"]>>;
  try {
    metadata = await sharp(bytes, sharpOptions).metadata();
  } catch {
    throw new Error(
      `Attachment ${attachmentId} could not be decoded as a valid raster image.`,
    );
  }
  const dimensions = validateGalleryDecodedMetadata(
    attachmentId,
    metadata,
    expectedContentType,
  );

  try {
    // metadata() only reads image headers. Decode every pixel and animation
    // frame into a discard stream so truncated or corrupt payloads cannot be
    // accepted without retaining another potentially large buffer.
    await pipeline(
      sharp(bytes, sharpOptions).raw(),
      new Writable({
        write(_chunk, _encoding, callback) {
          callback();
        },
      }),
    );
  } catch {
    throw new Error(
      `Attachment ${attachmentId} could not be fully decoded as a valid raster image.`,
    );
  }
  return dimensions;
}
