import "server-only";
import { parseGalleryMediaOrigin } from "../../gallery-media-origin";

export type GalleryMediaVariant = "listing" | "detail" | "lightbox";

const SAFE_KEY_SEGMENT = /^[A-Za-z0-9._-]+$/;
const TRANSFORM_OPTIONS: Record<GalleryMediaVariant, string> = {
  listing:
    "fit=scale-down,format=auto,metadata=none,onerror=redirect,width=960,anim=false",
  detail:
    "fit=scale-down,format=auto,metadata=none,onerror=redirect,width=1280,anim=false",
  lightbox:
    "fit=scale-down,format=auto,metadata=none,onerror=redirect,width=1920",
};

const GALLERY_MEDIA_ORIGIN = parseGalleryMediaOrigin(
  process.env.GALLERY_MEDIA_BASE_URL,
).origin;

function isGalleryStoragePath(pathname: string) {
  const segments = pathname.split("/");
  if (segments[0] !== "" || segments[1] !== "gallery" || segments.length < 3) {
    return false;
  }

  return segments
    .slice(2)
    .every(
      (segment) =>
        segment !== "." && segment !== ".." && SAFE_KEY_SEGMENT.test(segment),
    );
}

/**
 * Returns a directly purgeable Cloudflare image variant for a Gallery key.
 * Any URL outside the exact Gallery media origin/path is returned untouched.
 */
export function galleryMediaUrl(
  source: string,
  variant: GalleryMediaVariant,
) {
  let url: URL;
  try {
    url = new URL(source);
  } catch {
    return source;
  }

  if (
    url.origin !== GALLERY_MEDIA_ORIGIN ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== "" ||
    !isGalleryStoragePath(url.pathname)
  ) {
    return source;
  }

  return `${GALLERY_MEDIA_ORIGIN}/cdn-cgi/image/${TRANSFORM_OPTIONS[variant]}${url.pathname}`;
}
