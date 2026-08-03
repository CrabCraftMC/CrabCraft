import type { Metadata } from "next";
import { redirect } from "next/navigation";
import {
  getGalleryFilterOptions,
  getGalleryPosts,
} from "@crabcraft/db/queries/web";
import GalleryExplorer from "@/components/gallery/GalleryExplorer";
import {
  galleryHref,
  normaliseGalleryTagKey,
  parseGalleryTagParam,
} from "@/data/gallery";

const PAGE_SIZE = 12;
const MAX_PAGE = 10_000;

export const dynamic = "force-dynamic";

interface GalleryPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

function firstValue(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function parsePage(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) return 1;
  return Math.min(Math.max(Number(value), 1), MAX_PAGE);
}

function parseSeason(value: string | undefined) {
  if (!value || !/^\d{1,2}$/.test(value)) return null;
  return Number(value);
}

export async function generateMetadata({
  searchParams,
}: GalleryPageProps): Promise<Metadata> {
  const params = await searchParams;
  const season = parseSeason(firstValue(params.season));
  const tag = parseGalleryTagParam(firstValue(params.tag));
  const page = parsePage(firstValue(params.page));
  const isFiltered = season !== null || tag !== null;
  const canonical = isFiltered
    ? "/gallery"
    : galleryHref({ season: null, tag: null, page });
  const title =
    page > 1 && !isFiltered
      ? `Minecraft Screenshot Gallery - Page ${page}`
      : "Minecraft Screenshot Gallery";
  const description =
    "Explore Minecraft screenshots, builds and community moments shared by CrabCraft players across every season.";

  return {
    title,
    description,
    alternates: { canonical },
    openGraph: {
      title: `${title} - CrabCraft`,
      description,
      url: canonical,
      type: "website",
    },
  };
}

export default async function GalleryPage({ searchParams }: GalleryPageProps) {
  const params = await searchParams;
  const rawTag = firstValue(params.tag);
  const requestedSeason = parseSeason(firstValue(params.season));
  const requestedTag = parseGalleryTagParam(rawTag);
  const requestedPage = parsePage(firstValue(params.page));

  const [filters, result] = await Promise.all([
    getGalleryFilterOptions(),
    getGalleryPosts({
      season: requestedSeason ?? undefined,
      tag: requestedTag ?? undefined,
      limit: PAGE_SIZE,
      offset: (requestedPage - 1) * PAGE_SIZE,
    }),
  ]);

  const activeSeason =
    requestedSeason !== null && filters.seasons.includes(requestedSeason)
      ? requestedSeason
      : null;
  const requestedTagKey = requestedTag
    ? normaliseGalleryTagKey(requestedTag)
    : null;
  const activeTag =
    filters.tags.find((tag) => tag.key === requestedTagKey)?.key ?? null;

  if (
    activeSeason !== requestedSeason ||
    activeTag !== requestedTag ||
    (rawTag !== undefined && rawTag !== activeTag)
  ) {
    redirect(galleryHref({ season: activeSeason, tag: activeTag }));
  }

  const pageCount = Math.max(1, Math.ceil(result.total / PAGE_SIZE));
  if (requestedPage > pageCount) {
    redirect(
      galleryHref({ season: activeSeason, tag: activeTag, page: pageCount }),
    );
  }

  return (
    <div className="min-h-screen pb-16 pt-24">
      <div className="container mx-auto max-w-7xl px-4">
        <header className="mx-auto mb-10 max-w-2xl text-center animate-in motion-reduce:animate-none motion-reduce:opacity-100">
          <h1 className="font-mc text-4xl font-bold text-orange-600 dark:text-orange-500 lg:text-6xl">
            Gallery
          </h1>
          <p className="mt-3 text-base leading-relaxed text-gray-600 dark:text-gray-400 sm:text-lg">
            Builds, adventures and favourite moments from every CrabCraft season,
            shared by the players who made them.
          </p>
        </header>

        <GalleryExplorer
          posts={result.posts}
          seasons={filters.seasons}
          tags={filters.tags}
          activeSeason={activeSeason}
          activeTag={activeTag}
          page={requestedPage}
          pageCount={pageCount}
          total={result.total}
        />
      </div>
    </div>
  );
}
