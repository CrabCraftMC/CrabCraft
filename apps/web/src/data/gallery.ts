export type {
  GalleryFilterTag,
  GalleryImage,
  GalleryPost,
  GalleryPostLink,
  GalleryTag,
} from "@crabcraft/db/queries/web";

interface GalleryHrefOptions {
  season: number | null;
  tag: string | null;
  page?: number;
}

const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/;

export function normaliseGalleryTagKey(name: string) {
  return name.trim().toLocaleLowerCase("en-GB");
}

export function parseGalleryTagParam(value: string | undefined) {
  const tag = value?.trim();
  if (!tag || tag.length > 100 || CONTROL_CHARACTER.test(tag)) return null;
  return tag;
}

export function galleryHref({ season, tag, page = 1 }: GalleryHrefOptions) {
  const params = new URLSearchParams();
  if (season !== null) params.set("season", String(season));
  if (tag !== null) params.set("tag", tag);
  if (page > 1) params.set("page", String(page));
  const query = params.toString();
  return query ? `/gallery?${query}` : "/gallery";
}

export function formatGalleryDate(date: Date | string, includeTime = false) {
  return new Intl.DateTimeFormat(
    "en-GB",
    includeTime
      ? {
          day: "numeric",
          month: "long",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
          timeZoneName: "short",
          timeZone: "Europe/London",
        }
      : {
          day: "numeric",
          month: "short",
          year: "numeric",
          timeZone: "Europe/London",
        },
  ).format(new Date(date));
}
