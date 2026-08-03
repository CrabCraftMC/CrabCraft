import Image from "next/image";
import Link from "next/link";
import { Images, RotateCcw } from "lucide-react";
import GalleryTagIcon from "@/components/gallery/GalleryTagIcon";
import GalleryReactionList from "@/components/gallery/GalleryReactionList";
import GalleryPlayerSearch from "@/components/gallery/GalleryPlayerSearch";
import GalleryPagination from "@/components/gallery/GalleryPagination";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import { galleryMediaUrl } from "@/data/gallery-media";
import {
  formatGalleryDate,
  galleryHref,
  type GalleryFilterTag,
  type GalleryPlayerFilterOption,
  type GalleryPost,
} from "@/data/gallery";

interface GalleryExplorerProps {
  posts: GalleryPost[];
  seasons: number[];
  tags: GalleryFilterTag[];
  players: GalleryPlayerFilterOption[];
  activeSeason: number | null;
  activeTag: string | null;
  activePlayer: string | null;
  page: number;
  pageCount: number;
  total: number;
}

function cardImageClass(imageCount: number, index: number) {
  if (imageCount === 1) return "col-span-2 row-span-2";
  if (imageCount === 2) return "row-span-2";
  if (imageCount === 3 && index === 0) return "row-span-2";
  return "";
}

function GalleryAuthorByline({ post }: { post: GalleryPost }) {
  const contents = (
    <>
      <PixelIcon
        src={post.author.avatarUrl}
        alt={`${post.author.username}'s avatar`}
        size={28}
        imgClassName="rounded-md"
      />
      <span className="truncate text-xs">{post.author.username}</span>
    </>
  );

  return post.author.profileHref ? (
    <Link
      href={post.author.profileHref}
      className="flex min-w-0 items-center gap-2.5 font-bold text-gray-700 transition-colors hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
    >
      {contents}
    </Link>
  ) : (
    <div className="flex min-w-0 items-center gap-2.5 font-bold text-gray-700 dark:text-gray-300">
      {contents}
    </div>
  );
}

export default function GalleryExplorer({
  posts,
  seasons,
  tags,
  players,
  activeSeason,
  activeTag,
  activePlayer,
  page,
  pageCount,
  total,
}: GalleryExplorerProps) {
  const activeTagRecord = tags.find((tag) => tag.key === activeTag) ?? null;
  const orderedSeasons = [...seasons].sort((a, b) => b - a);

  return (
    <>
      <div
        className="mb-8 rounded-[28px] bg-paper-2 p-4 sm:p-5 animate-in motion-reduce:animate-none motion-reduce:opacity-100"
        style={{ animationDelay: "0.08s" }}
      >
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2 lg:flex-row lg:items-center">
            <span className="w-20 shrink-0 text-xs font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
              Season
            </span>
            <div className="flex flex-wrap gap-2">
              <Link
                href={galleryHref({
                  season: null,
                  tag: activeTag,
                  player: activePlayer,
                })}
                aria-current={activeSeason === null ? "page" : undefined}
                className={`rounded-full px-3.5 py-2 text-xs font-bold transition-colors ${
                  activeSeason === null
                    ? "bg-orange-200 text-orange-950 dark:bg-orange-400 dark:text-gray-950"
                    : "bg-paper text-gray-700 hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
                }`}
              >
                All
              </Link>
              {orderedSeasons.map((season) => (
                <Link
                  key={season}
                  href={galleryHref({
                    season,
                    tag: activeTag,
                    player: activePlayer,
                  })}
                  aria-current={activeSeason === season ? "page" : undefined}
                  className={`rounded-full px-3.5 py-2 text-xs font-bold transition-colors ${
                    activeSeason === season
                      ? "bg-orange-200 text-orange-950 dark:bg-orange-400 dark:text-gray-950"
                      : "bg-paper text-gray-700 hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
                  }`}
                >
                  Season {season}
                </Link>
              ))}
            </div>
          </div>

          <div className="h-px bg-line" />

          <div className="flex flex-col gap-2 lg:flex-row lg:items-center">
            <span className="w-20 shrink-0 text-xs font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
              Tag
            </span>
            <div className="flex flex-wrap gap-2">
              <Link
                href={galleryHref({
                  season: activeSeason,
                  tag: null,
                  player: activePlayer,
                })}
                aria-current={activeTagRecord === null ? "page" : undefined}
                className={`rounded-full px-3.5 py-2 text-xs font-bold transition-colors ${
                  activeTagRecord === null
                    ? "bg-gray-800 text-white dark:bg-white dark:text-gray-950"
                    : "bg-paper text-gray-700 hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
                }`}
              >
                All tags
              </Link>
              {tags.map((tag) => (
                <Link
                  key={tag.key}
                  href={galleryHref({
                    season: activeSeason,
                    tag: tag.key,
                    player: activePlayer,
                  })}
                  aria-current={activeTag === tag.key ? "page" : undefined}
                  className={`inline-flex items-center gap-1.5 rounded-full px-3.5 py-2 text-xs font-bold transition-colors ${
                    activeTag === tag.key
                      ? "bg-gray-800 text-white dark:bg-white dark:text-gray-950"
                      : "bg-paper text-gray-700 hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
                  }`}
                >
                  <GalleryTagIcon
                    emojiName={tag.emojiName}
                    emojiUrl={tag.emojiUrl}
                  />
                  {tag.name}
                </Link>
              ))}
            </div>
          </div>

          <div className="h-px bg-line" />

          <GalleryPlayerSearch
            players={players}
            activePlayer={activePlayer}
            activeSeason={activeSeason}
            activeTag={activeTag}
          />
        </div>
      </div>

      <div className="mb-4 flex items-center justify-between gap-4">
        <p className="text-sm text-gray-600 dark:text-gray-400">
          {total} {total === 1 ? "submission" : "submissions"}
        </p>
        {(activeSeason !== null ||
          activeTagRecord !== null ||
          activePlayer !== null) && (
          <Link
            href="/gallery"
            className="flex items-center gap-1.5 text-xs font-bold text-gray-600 transition-colors hover:text-orange-700 dark:text-gray-400 dark:hover:text-orange-400"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Clear filters
          </Link>
        )}
      </div>

      {posts.length > 0 ? (
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3">
          {posts.map((post, index) => (
            <article
              key={post.id}
              className="group animate-in overflow-hidden rounded-[2rem] bg-paper-2 shadow-sm motion-reduce:animate-none motion-reduce:opacity-100"
              style={{ animationDelay: `${0.12 + index * 0.05}s` }}
            >
              <Link
                href={`/gallery/${post.id}`}
                aria-label={`View ${post.title}`}
                className="relative grid aspect-[16/10] grid-cols-2 grid-rows-2 gap-0.5 overflow-hidden bg-[#130f0c] focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-orange-500/60 focus-visible:ring-inset"
              >
                {post.images.slice(0, 4).map((image, imageIndex) => (
                  <span
                    key={image.id}
                    className={`relative min-h-0 min-w-0 overflow-hidden ${cardImageClass(post.images.length, imageIndex)}`}
                  >
                    <Image
                      src={galleryMediaUrl(image.url, "listing")}
                      alt={image.alt ?? `${post.title} — image ${imageIndex + 1}`}
                      fill
                      sizes="(max-width: 767px) 100vw, (max-width: 1279px) 50vw, 33vw"
                      className="object-cover"
                      priority={index < 2 && imageIndex === 0}
                      unoptimized
                    />
                  </span>
                ))}
                <div className="absolute inset-x-0 bottom-0 h-24 bg-gradient-to-t from-black/60 to-transparent" />
                {post.images.length > 1 && (
                  <span className="absolute right-4 top-4 flex items-center gap-1.5 rounded-full bg-black/70 px-3 py-1.5 text-xs font-bold text-white backdrop-blur-md">
                    <Images className="h-3.5 w-3.5" />
                    {post.images.length} photos
                  </span>
                )}
              </Link>

              <div className="p-5">
                <div className="mb-3 flex flex-wrap items-center gap-2">
                  <Link
                    href={galleryHref({
                      season: post.season,
                      tag: activeTag,
                      player: activePlayer,
                    })}
                    className="rounded-full bg-orange-100 px-3 py-1 text-[11px] font-bold text-orange-900 transition-colors hover:bg-orange-200 dark:bg-orange-500/20 dark:text-orange-300 dark:hover:bg-orange-500/30"
                  >
                    Season {post.season}
                  </Link>
                  {post.tags.slice(0, 2).map((tag) => {
                    const contents = (
                      <>
                        <GalleryTagIcon
                          emojiName={tag.emojiName}
                          emojiUrl={tag.emojiUrl}
                          size={12}
                        />
                        {tag.name}
                      </>
                    );
                    const className =
                      "inline-flex items-center gap-1 rounded-full bg-paper px-2.5 py-1 text-[11px] font-bold text-gray-600 dark:text-gray-400";

                    return tags.some(
                      (filterTag) => filterTag.key === tag.filterKey,
                    ) ? (
                      <Link
                        key={tag.id}
                        href={galleryHref({
                          season: activeSeason,
                          tag: tag.filterKey,
                          player: activePlayer,
                        })}
                        className={`${className} transition-colors hover:text-orange-700 dark:hover:text-orange-400`}
                      >
                        {contents}
                      </Link>
                    ) : (
                      <span key={tag.id} className={className}>
                        {contents}
                      </span>
                    );
                  })}
                </div>

                <Link href={`/gallery/${post.id}`} className="block min-w-0">
                  <h2 className="break-words text-lg font-bold leading-snug text-gray-900 transition-colors group-hover:text-orange-700 dark:text-gray-100 dark:group-hover:text-orange-400">
                    {post.title}
                  </h2>
                  {post.content ? (
                    <p className="mt-2 line-clamp-2 break-words whitespace-pre-line text-sm leading-relaxed text-gray-600 dark:text-gray-400">
                      {post.content}
                    </p>
                  ) : null}
                </Link>

                {post.reactions.length > 0 ? (
                  <div className="mt-4">
                    <GalleryReactionList reactions={post.reactions} limit={4} />
                  </div>
                ) : null}

                <div className="mt-5 flex items-center justify-between gap-3 border-t border-line pt-4">
                  <GalleryAuthorByline post={post} />
                  <time
                    dateTime={post.postedAt.toISOString()}
                    className="shrink-0 text-[11px] text-gray-500 dark:text-gray-400"
                  >
                    {formatGalleryDate(post.postedAt)}
                  </time>
                </div>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <Squircle cornerRadius={28} className="bg-paper-2 px-6 py-16 text-center">
          <Images className="mx-auto h-8 w-8 text-gray-400 dark:text-gray-600" />
          {activeSeason !== null ||
          activeTagRecord !== null ||
          activePlayer !== null ? (
            <>
              <h2 className="mt-4 text-lg font-bold">No screenshots match</h2>
              <p className="mt-1 text-sm text-gray-600 dark:text-gray-400">
                Try another player or season, or clear the current filters.
              </p>
            </>
          ) : (
            <>
              <h2 className="mt-4 text-lg font-bold">No screenshots yet</h2>
              <p className="mt-1 text-sm text-gray-600 dark:text-gray-400">
                The first community submissions will appear here automatically.
              </p>
            </>
          )}
        </Squircle>
      )}

      {pageCount > 1 ? (
        <GalleryPagination
          page={page}
          pageCount={pageCount}
          season={activeSeason}
          tag={activeTagRecord?.key ?? null}
          player={activePlayer}
        />
      ) : null}
    </>
  );
}
