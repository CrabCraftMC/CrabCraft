"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { galleryHref } from "@/data/gallery";

export default function GalleryPagination({
  page,
  pageCount,
  season,
  tag,
  player,
}: {
  page: number;
  pageCount: number;
  season: number | null;
  tag: string | null;
  player: string | null;
}) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [editing, setEditing] = useState(false);
  const [draftPage, setDraftPage] = useState(String(page));

  useEffect(() => {
    setDraftPage(String(page));
  }, [page]);

  useEffect(() => {
    if (!editing) return;
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [editing]);

  const href = (targetPage: number) =>
    galleryHref({ season, tag, player, page: targetPage });

  const submitPage = () => {
    const requestedPage = Number(draftPage);
    if (!Number.isSafeInteger(requestedPage)) {
      setDraftPage(String(page));
      setEditing(false);
      return;
    }
    const targetPage = Math.min(Math.max(requestedPage, 1), pageCount);
    setDraftPage(String(targetPage));
    setEditing(false);
    if (targetPage !== page) {
      router.push(href(targetPage), { scroll: false });
    }
  };

  return (
    <nav
      aria-label="Gallery pages"
      className="mt-10 flex flex-wrap items-center justify-center gap-3"
    >
      {page > 1 ? (
        <Link
          rel="prev"
          href={href(page - 1)}
          scroll={false}
          className="inline-flex items-center gap-1.5 rounded-full bg-paper-2 px-4 py-2.5 text-sm font-bold text-gray-700 transition-colors hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
        >
          <ChevronLeft className="h-4 w-4" />
          Previous
        </Link>
      ) : null}

      <span className="flex items-center gap-1 text-sm font-medium text-gray-600 dark:text-gray-400">
        Page
        {editing ? (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              submitPage();
            }}
          >
            <input
              ref={inputRef}
              type="number"
              min={1}
              max={pageCount}
              step={1}
              value={draftPage}
              aria-label={`Choose a Gallery page from 1 to ${pageCount}`}
              className="h-8 w-14 rounded-lg bg-paper-2 px-1 text-center text-sm font-bold text-gray-800 outline-none focus-visible:ring-2 focus-visible:ring-orange-500 dark:text-gray-200"
              onChange={(event) => setDraftPage(event.target.value)}
              onBlur={() => {
                setDraftPage(String(page));
                setEditing(false);
              }}
              onKeyDown={(event) => {
                if (event.key === "Escape") {
                  setDraftPage(String(page));
                  setEditing(false);
                }
              }}
            />
          </form>
        ) : (
          <button
            type="button"
            onClick={() => setEditing(true)}
            aria-label={`Current page ${page}. Click to choose another page.`}
            className="cursor-pointer rounded-md px-1.5 py-1 font-bold text-gray-800 underline decoration-dotted underline-offset-4 hover:text-orange-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-500 dark:text-gray-200 dark:hover:text-orange-400"
          >
            {page}
          </button>
        )}
        of {pageCount}
      </span>

      {page < pageCount ? (
        <Link
          rel="next"
          href={href(page + 1)}
          scroll={false}
          className="inline-flex items-center gap-1.5 rounded-full bg-paper-2 px-4 py-2.5 text-sm font-bold text-gray-700 transition-colors hover:text-orange-700 dark:text-gray-300 dark:hover:text-orange-400"
        >
          Next
          <ChevronRight className="h-4 w-4" />
        </Link>
      ) : null}
    </nav>
  );
}
