import { cache } from "react";
import type { Metadata } from "next";
import Link from "next/link";
import Script from "next/script";
import { notFound } from "next/navigation";
import {
  getAdjacentGalleryPosts,
  getGalleryPost,
} from "@crabcraft/db/queries/web";
import { ArrowLeft, CalendarDays, Images, MessageSquareText } from "lucide-react";
import GalleryFilterLink from "@/components/gallery/GalleryFilterLink";
import GalleryMediaViewer from "@/components/gallery/GalleryMediaViewer";
import GalleryTagIcon from "@/components/gallery/GalleryTagIcon";
import GalleryReactionList from "@/components/gallery/GalleryReactionList";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import {
  formatGalleryDate,
  galleryHref,
} from "@/data/gallery";
import { galleryMediaUrl } from "@/data/gallery-media";
import { playerDisplayName } from "@/lib/playerName";

const SITE_URL = "https://crabcraft.net";

export const dynamic = "force-dynamic";
export const unstable_dynamicStaleTime = 30;

interface GalleryPostPageProps {
  params: Promise<{ id: string }>;
}

const getCachedGalleryPost = cache(getGalleryPost);

function serialiseJsonLd(value: unknown) {
  return JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");
}

function absoluteUrl(value: string) {
  return new URL(value, SITE_URL).toString();
}

function metadataDescription(content: string | null, fallback: string) {
  const description = content?.replace(/\s+/g, " ").trim() || fallback;
  return description.length > 160
    ? `${description.slice(0, 157).trimEnd()}…`
    : description;
}

export async function generateMetadata({
  params,
}: GalleryPostPageProps): Promise<Metadata> {
  const { id } = await params;
  const post = await getCachedGalleryPost(id);
  if (!post) return { title: "Gallery post not found" };
  const authorName = playerDisplayName(
    post.author.nickname,
    post.author.username,
  );

  const description = metadataDescription(
    post.content,
    `Minecraft screenshots shared by ${authorName} during CrabCraft Season ${post.season}.`,
  );
  const canonical = `/gallery/${post.id}`;
  const images = post.images.map((image, index) => ({
    url: image.url,
    alt: image.alt ?? `${post.title} — image ${index + 1}`,
    ...(image.width ? { width: image.width } : {}),
    ...(image.height ? { height: image.height } : {}),
  }));

  return {
    title: post.title,
    description,
    alternates: { canonical },
    openGraph: {
      title: `${post.title} - CrabCraft Gallery`,
      description,
      url: canonical,
      type: "article",
      publishedTime: post.postedAt.toISOString(),
      modifiedTime: post.updatedAt.toISOString(),
      authors: [authorName],
      tags: post.tags.map((tag) => tag.name),
      images,
    },
    twitter: {
      card: images.length > 0 ? "summary_large_image" : "summary",
      images: images[0] ? [images[0].url] : undefined,
    },
  };
}

export default async function GalleryPostPage({ params }: GalleryPostPageProps) {
  const { id } = await params;
  const [post, adjacentPosts] = await Promise.all([
    getCachedGalleryPost(id),
    getAdjacentGalleryPosts(id),
  ]);
  if (!post) notFound();
  const authorName = playerDisplayName(
    post.author.nickname,
    post.author.username,
  );

  const pageUrl = `${SITE_URL}/gallery/${post.id}`;
  const authorUrl = post.author.profileHref
    ? absoluteUrl(post.author.profileHref)
    : undefined;
  const viewerImages = post.images.map((image, index) => ({
    id: image.id,
    alt: image.alt ?? `${post.title} — image ${index + 1}`,
    detailUrl: galleryMediaUrl(image.url, "detail"),
    lightboxUrl: galleryMediaUrl(image.url, "lightbox"),
  }));
  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "ImageGallery",
    name: post.title,
    url: pageUrl,
    description: metadataDescription(
      post.content,
      `CrabCraft Season ${post.season} screenshots by ${authorName}.`,
    ),
    datePublished: post.postedAt.toISOString(),
    dateModified: post.updatedAt.toISOString(),
    author: {
      "@type": "Person",
      name: authorName,
      ...(authorUrl ? { url: authorUrl } : {}),
    },
    keywords: post.tags.map((tag) => tag.name),
    image: post.images.map((image, index) => ({
      "@type": "ImageObject",
      contentUrl: absoluteUrl(image.url),
      caption: image.alt ?? `${post.title} — image ${index + 1}`,
      ...(image.width ? { width: image.width } : {}),
      ...(image.height ? { height: image.height } : {}),
    })),
    isPartOf: {
      "@type": "CollectionPage",
      name: "CrabCraft Gallery",
      url: `${SITE_URL}/gallery`,
    },
  };

  return (
    <div className="min-h-screen pb-16 pt-24">
      <Script
        id={`gallery-jsonld-${post.id}`}
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: serialiseJsonLd(jsonLd) }}
      />
      <div className="container mx-auto max-w-7xl px-4">
        <GalleryFilterLink
          href="/gallery"
          className="mb-6 inline-flex items-center gap-2 text-sm font-bold text-gray-600 transition-colors hover:text-orange-700 dark:text-gray-400 dark:hover:text-orange-400"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to Gallery
        </GalleryFilterLink>

        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1.7fr)_minmax(19rem,0.8fr)] lg:gap-8">
          <section
            aria-label="Submission images"
            className="min-w-0 animate-in motion-reduce:animate-none motion-reduce:opacity-100"
          >
            <GalleryMediaViewer images={viewerImages} />
          </section>

          <Squircle
            cornerRadius={32}
            className="bg-paper-2 p-6 animate-in motion-reduce:animate-none motion-reduce:opacity-100 lg:sticky lg:top-28 sm:p-7"
            style={{ animationDelay: "0.08s" }}
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded-full bg-orange-100 px-3 py-1.5 text-xs font-bold text-orange-900 dark:bg-orange-500/20 dark:text-orange-300">
                Season {post.season}
              </span>
              <span className="flex items-center gap-1.5 rounded-full bg-paper px-3 py-1.5 text-xs font-bold text-gray-600 dark:text-gray-400">
                <Images className="h-3.5 w-3.5" />
                {post.images.length} {post.images.length === 1 ? "image" : "images"}
              </span>
            </div>

            <h1 className="mt-5 break-words text-3xl font-bold leading-tight text-gray-900 dark:text-gray-100">
              {post.title}
            </h1>

            {post.content ? (
              <div className="mt-5 flex min-w-0 items-start gap-3 text-sm leading-relaxed text-gray-700 dark:text-gray-300">
                <MessageSquareText className="mt-0.5 h-4 w-4 shrink-0 text-orange-600 dark:text-orange-500" />
                <p className="min-w-0 whitespace-pre-wrap break-words">{post.content}</p>
              </div>
            ) : (
              <p className="mt-5 text-sm italic text-gray-500 dark:text-gray-400">
                This submission did not include a message.
              </p>
            )}

            {post.tags.length > 0 ? (
              <div className="mt-6 flex flex-wrap gap-2">
                {post.tags.map((tag) => (
                  <Link
                    key={tag.id}
                    href={galleryHref({
                      season: null,
                      tag: tag.filterKey,
                    })}
                    className="inline-flex items-center gap-1.5 rounded-full bg-paper px-3 py-1.5 text-xs font-bold text-gray-700 transition-colors hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
                  >
                    <GalleryTagIcon
                      emojiName={tag.emojiName}
                      emojiUrl={tag.emojiUrl}
                    />
                    {tag.name}
                  </Link>
                ))}
              </div>
            ) : null}

            {post.reactions.length > 0 ? (
              <div className="mt-6">
                <span className="mb-2 block text-[10px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                  Discord reactions
                </span>
                <GalleryReactionList reactions={post.reactions} />
              </div>
            ) : null}

            <div className="mt-7 border-t border-line pt-6">
              {post.author.profileHref ? (
                <Link
                  href={post.author.profileHref}
                  className="group flex items-center gap-3 rounded-2xl bg-paper p-3 transition-colors hover:bg-orange-500/10"
                >
                  <PixelIcon
                    src={post.author.avatarUrl}
                    alt={`${authorName}'s avatar`}
                    size={44}
                    imgClassName="rounded-lg"
                  />
                  <span className="min-w-0">
                    <span className="block text-[10px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                      Submitted by
                    </span>
                    <span className="block truncate text-sm font-bold text-gray-800 transition-colors group-hover:text-orange-700 dark:text-gray-200 dark:group-hover:text-orange-400">
                      {authorName}
                    </span>
                  </span>
                </Link>
              ) : (
                <div className="flex items-center gap-3 rounded-2xl bg-paper p-3">
                  <PixelIcon
                    src={post.author.avatarUrl}
                    alt={`${authorName}'s avatar`}
                    size={44}
                    imgClassName="rounded-lg"
                  />
                  <span className="min-w-0">
                    <span className="block text-[10px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                      Submitted by
                    </span>
                    <span className="block truncate text-sm font-bold text-gray-800 dark:text-gray-200">
                      {authorName}
                    </span>
                  </span>
                </div>
              )}

              <div className="mt-4 flex items-start gap-3 px-1 text-sm text-gray-600 dark:text-gray-400">
                <CalendarDays className="mt-0.5 h-4 w-4 shrink-0 text-orange-600 dark:text-orange-500" />
                <span>
                  <span className="block text-[10px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                    Posted
                  </span>
                  <time
                    dateTime={post.postedAt.toISOString()}
                    className="mt-0.5 block font-medium"
                  >
                    {formatGalleryDate(post.postedAt, true)}
                  </time>
                </span>
              </div>
            </div>
          </Squircle>
        </div>

        <nav
          aria-label="Gallery post navigation"
          className="mt-8 grid gap-3 sm:grid-cols-2"
        >
          {adjacentPosts.newer ? (
            <Link
              rel="prev"
              href={`/gallery/${adjacentPosts.newer.id}`}
              className="rounded-2xl bg-paper-2 p-4 transition-colors hover:bg-orange-500/10"
            >
              <span className="block text-[10px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                Newer submission
              </span>
              <span className="mt-1 block break-words font-bold text-gray-800 dark:text-gray-200">
                {adjacentPosts.newer.title}
              </span>
            </Link>
          ) : null}
          {adjacentPosts.older ? (
            <Link
              rel="next"
              href={`/gallery/${adjacentPosts.older.id}`}
              className={`rounded-2xl bg-paper-2 p-4 text-right transition-colors hover:bg-orange-500/10 ${!adjacentPosts.newer ? "sm:col-start-2" : ""}`}
            >
              <span className="block text-[10px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                Older submission
              </span>
              <span className="mt-1 block break-words font-bold text-gray-800 dark:text-gray-200">
                {adjacentPosts.older.title}
              </span>
            </Link>
          ) : null}
        </nav>

        <div className="mt-6 text-center">
          <Link
            href={`/gallery?season=${post.season}`}
            className="text-sm font-bold text-gray-600 transition-colors hover:text-orange-700 dark:text-gray-400 dark:hover:text-orange-400"
          >
            More from Season {post.season}
          </Link>
        </div>
      </div>
    </div>
  );
}
