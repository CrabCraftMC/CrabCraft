export type {
  GalleryFilterTag,
  GalleryImage,
  GalleryPost,
  GalleryPostLink,
  GalleryPlayerFilterOption,
  GalleryReaction,
  GalleryTag,
} from "@crabcraft/db/queries/web";

interface GalleryHrefOptions {
  season: number | null;
  tag: string | null;
  player?: string | null;
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

export function parseGalleryPlayerParam(value: string | undefined) {
  const player = value?.trim();
  if (!player || player.length > 32 || CONTROL_CHARACTER.test(player)) return null;
  return player;
}

export function galleryHref({
  season,
  tag,
  player,
  page = 1,
}: GalleryHrefOptions) {
  const params = new URLSearchParams();
  if (season !== null) params.set("season", String(season));
  if (tag !== null) params.set("tag", tag);
  if (player) params.set("player", player);
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
