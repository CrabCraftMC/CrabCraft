"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Search } from "lucide-react";
import PixelIcon from "@/components/PixelIcon";
import type { GalleryPlayerFilterOption } from "@/data/gallery";

export default function GalleryPlayerSearch({
  players,
  activePlayer,
  activeSeason,
  activeTag,
}: {
  players: GalleryPlayerFilterOption[];
  activePlayer: string | null;
  activeSeason: number | null;
  activeTag: string | null;
}) {
  const formRef = useRef<HTMLFormElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const listboxId = useId();
  const [value, setValue] = useState(activePlayer ?? "");
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const matches = useMemo(() => {
    const query = value.trim().toLocaleLowerCase("en-GB");
    return query
      ? players.filter((player) =>
          player.username.toLocaleLowerCase("en-GB").includes(query),
        )
      : players;
  }, [players, value]);
  const showDropdown = open && matches.length > 0;

  useEffect(() => {
    if (!showDropdown) return;
    document
      .getElementById(`${listboxId}-${activeIndex}`)
      ?.scrollIntoView({ block: "nearest" });
  }, [activeIndex, listboxId, showDropdown]);

  const choosePlayer = (player: GalleryPlayerFilterOption) => {
    setValue(player.username);
    setOpen(false);
    requestAnimationFrame(() => formRef.current?.requestSubmit());
  };

  return (
    <form
      ref={formRef}
      action="/gallery"
      method="get"
      className="flex flex-col gap-2 lg:flex-row lg:items-center"
    >
      <label
        htmlFor="gallery-player-search"
        className="w-20 shrink-0 text-xs font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400"
      >
        Player
      </label>
      {activeSeason !== null ? (
        <input type="hidden" name="season" value={activeSeason} />
      ) : null}
      {activeTag !== null ? (
        <input type="hidden" name="tag" value={activeTag} />
      ) : null}
      <div className="flex w-full max-w-md gap-2">
        <div
          ref={containerRef}
          className="relative min-w-0 flex-1"
          onBlur={(event) => {
            if (
              !containerRef.current?.contains(event.relatedTarget as Node | null)
            ) {
              setOpen(false);
            }
          }}
        >
          <input
            id="gallery-player-search"
            name="player"
            type="search"
            value={value}
            maxLength={32}
            placeholder="Search by player name"
            autoComplete="off"
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={showDropdown}
            aria-controls={listboxId}
            aria-activedescendant={
              showDropdown ? `${listboxId}-${activeIndex}` : undefined
            }
            className="w-full rounded-full bg-paper px-4 py-2 text-sm text-gray-800 outline-none placeholder:text-gray-400 focus-visible:ring-2 focus-visible:ring-orange-500 dark:text-gray-200"
            onFocus={() => {
              setActiveIndex(0);
              setOpen(true);
            }}
            onChange={(event) => {
              setValue(event.target.value);
              setActiveIndex(0);
              setOpen(true);
            }}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown" && matches.length > 0) {
                event.preventDefault();
                setOpen(true);
                setActiveIndex((current) =>
                  Math.min(current + 1, matches.length - 1),
                );
              } else if (event.key === "ArrowUp" && matches.length > 0) {
                event.preventDefault();
                setOpen(true);
                setActiveIndex((current) => Math.max(current - 1, 0));
              } else if (event.key === "Enter" && showDropdown) {
                event.preventDefault();
                const player = matches[activeIndex];
                if (player) choosePlayer(player);
              } else if (event.key === "Escape") {
                setOpen(false);
              }
            }}
          />

          {showDropdown ? (
            <div
              id={listboxId}
              role="listbox"
              aria-label="Gallery players"
              className="absolute left-0 right-0 top-full z-30 mt-2 max-h-72 overflow-y-auto rounded-2xl border border-line bg-paper-2 p-1.5 shadow-xl"
            >
              {matches.map((player, index) => (
                <button
                  id={`${listboxId}-${index}`}
                  key={`${player.username}-${index}`}
                  type="button"
                  role="option"
                  aria-selected={index === activeIndex}
                  className={`flex w-full cursor-pointer items-center gap-3 rounded-xl px-3 py-2 text-left text-sm font-bold ${
                    index === activeIndex
                      ? "bg-orange-500/15 text-orange-800 dark:text-orange-300"
                      : "text-gray-700 hover:bg-paper dark:text-gray-300"
                  }`}
                  onMouseDown={(event) => event.preventDefault()}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => choosePlayer(player)}
                >
                  <PixelIcon
                    src={player.avatarUrl}
                    alt=""
                    size={30}
                    imgClassName="rounded-md"
                  />
                  <span className="min-w-0 truncate">{player.username}</span>
                </button>
              ))}
            </div>
          ) : null}
        </div>
        <button
          type="submit"
          className="inline-flex cursor-pointer items-center gap-1.5 rounded-full bg-gray-800 px-4 py-2 text-xs font-bold text-white transition-colors hover:bg-orange-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-500 dark:bg-white dark:text-gray-950 dark:hover:bg-orange-400"
        >
          <Search className="h-3.5 w-3.5" />
          Search
        </button>
      </div>
    </form>
  );
}
