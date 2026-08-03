export interface GalleryCloudflarePurgeConfig {
  zoneId: string;
  token: string;
}

type FetchLike = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

const CLOUDFLARE_PURGE_TIMEOUT_MS = 10_000;

export function parseGalleryCloudflarePurgeConfig(
  zoneId: string | undefined,
  token: string | undefined,
  required: true,
): GalleryCloudflarePurgeConfig;
export function parseGalleryCloudflarePurgeConfig(
  zoneId: string | undefined,
  token: string | undefined,
  required?: false,
): GalleryCloudflarePurgeConfig | null;
export function parseGalleryCloudflarePurgeConfig(
  zoneId: string | undefined,
  token: string | undefined,
  required = false,
): GalleryCloudflarePurgeConfig | null {
  const normalisedZoneId = zoneId?.trim() ?? "";
  const normalisedToken = token?.trim() ?? "";
  if (Boolean(normalisedZoneId) !== Boolean(normalisedToken)) {
    throw new Error(
      "GALLERY_CLOUDFLARE_ZONE_ID and GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN must be configured together.",
    );
  }
  if (!normalisedZoneId) {
    if (required) {
      throw new Error(
        "Gallery S3 storage requires GALLERY_CLOUDFLARE_ZONE_ID and GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN.",
      );
    }
    return null;
  }
  return { zoneId: normalisedZoneId, token: normalisedToken };
}

export function buildGalleryPublicUrl(
  publicBaseUrl: string,
  storageKey: string,
): string {
  const baseUrl = new URL(publicBaseUrl);
  if (
    baseUrl.protocol !== "https:" ||
    baseUrl.username ||
    baseUrl.password ||
    baseUrl.pathname !== "/" ||
    baseUrl.search ||
    baseUrl.hash
  ) {
    throw new Error(
      "GALLERY_MEDIA_BASE_URL must be a credential-free HTTPS origin without a path, query or fragment.",
    );
  }

  if (
    !storageKey.startsWith("gallery/") ||
    !/^[A-Za-z0-9._/-]+$/.test(storageKey) ||
    storageKey
      .split("/")
      .some((segment) => !segment || segment === "." || segment === "..")
  ) {
    throw new Error(`Invalid gallery storage key: ${storageKey}`);
  }

  const normalisedBase = `${baseUrl.toString().replace(/\/+$/, "")}/`;
  const publicUrl = new URL(storageKey, normalisedBase);
  if (publicUrl.origin !== baseUrl.origin) {
    throw new Error(`Gallery storage key escaped the media origin: ${storageKey}`);
  }
  return publicUrl.toString();
}

export function validateGalleryPurgeUrl(publicUrl: string): string {
  const url = new URL(publicUrl);
  if (url.protocol !== "https:" || url.username || url.password) {
    throw new Error("Gallery cache purge URL must be a credential-free HTTPS URL.");
  }
  return url.toString();
}

export function bindGalleryPurgeUrl(
  expectedPublicUrl: string,
  queuedPublicUrl: string,
): string {
  if (queuedPublicUrl !== expectedPublicUrl) {
    throw new Error(
      "Queued Gallery media URL does not match its canonical storage URL.",
    );
  }
  return validateGalleryPurgeUrl(expectedPublicUrl);
}

export function validateCloudflarePurgeResponse(value: unknown): void {
  if (
    typeof value !== "object" ||
    value === null ||
    !("success" in value) ||
    value.success !== true ||
    !("errors" in value) ||
    !Array.isArray(value.errors)
  ) {
    throw new Error("Cloudflare did not confirm the gallery cache purge.");
  }
}

function isJsonContentType(value: string | null): boolean {
  const mediaType = value?.split(";", 1)[0]?.trim().toLowerCase();
  return (
    mediaType === "application/json" || mediaType?.endsWith("+json") === true
  );
}

export async function purgeGalleryFileFromCloudflare(
  purgeConfig: GalleryCloudflarePurgeConfig,
  publicUrl: string,
  fetchImpl: FetchLike = fetch,
): Promise<void> {
  const purgeUrl = validateGalleryPurgeUrl(publicUrl);
  const endpoint = new URL(
    `https://api.cloudflare.com/client/v4/zones/${encodeURIComponent(purgeConfig.zoneId)}/purge_cache`,
  );
  if (endpoint.protocol !== "https:") {
    throw new Error("Cloudflare cache purge API must use HTTPS.");
  }

  const response = await fetchImpl(endpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${purgeConfig.token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ files: [purgeUrl] }),
    redirect: "error",
    signal: AbortSignal.timeout(CLOUDFLARE_PURGE_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw new Error(`Cloudflare cache purge returned HTTP ${response.status}.`);
  }
  if (!isJsonContentType(response.headers.get("content-type"))) {
    throw new Error("Cloudflare cache purge returned a non-JSON response.");
  }

  let result: unknown;
  try {
    result = await response.json();
  } catch {
    throw new Error("Cloudflare cache purge returned invalid JSON.");
  }
  validateCloudflarePurgeResponse(result);
}
