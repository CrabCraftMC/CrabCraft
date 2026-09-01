"use client";

import {
  startTransition,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import PixelIcon from "@/components/PixelIcon";
import { playerDisplayName } from "@/lib/playerName";
import { trackUmamiEvent } from "@/lib/umami";
import {
  galleryHref,
  type GalleryPlayerFilterOption,
} from "@/data/gallery";

function galleryPlayerName(player: GalleryPlayerFilterOption) {
  return playerDisplayName(player.nickname, player.username);
}

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
  const router = useRouter();
  const containerRef = useRef<HTMLDivElement>(null);
  const listboxRef = useRef<HTMLDivElement>(null);
  const listboxId = useId();
  const initialPlayer =
    players.find((player) => player.username === activePlayer) ?? null;
  const [value, setValue] = useState(
    initialPlayer ? galleryPlayerName(initialPlayer) : activePlayer ?? "",
  );
  const [selectedPlayer, setSelectedPlayer] =
    useState<GalleryPlayerFilterOption | null>(initialPlayer);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [listboxPosition, setListboxPosition] = useState<{
    left: number;
    top: number;
    width: number;
  } | null>(null);
  const matches = useMemo(() => {
    const query = value.trim().toLocaleLowerCase("en-GB");
    return query
      ? players.filter((player) => {
          const displayName = galleryPlayerName(player).toLocaleLowerCase("en-GB");
          return (
            displayName.includes(query) ||
            player.username.toLocaleLowerCase("en-GB").includes(query)
          );
        })
      : players;
  }, [players, value]);
  const showDropdown = open && matches.length > 0;

  useEffect(() => {
    const player =
      players.find((option) => option.username === activePlayer) ?? null;
    setValue(player ? galleryPlayerName(player) : activePlayer ?? "");
    setSelectedPlayer(player);
  }, [activePlayer, players]);

  useLayoutEffect(() => {
    if (!showDropdown) {
      setListboxPosition(null);
      return;
    }

    const reposition = () => {
      const container = containerRef.current;
      if (!container) return;
      const rect = container.getBoundingClientRect();
      const width = Math.min(rect.width, window.innerWidth - 16);
      const left = Math.min(
        Math.max(rect.left, 8),
        window.innerWidth - width - 8,
      );
      setListboxPosition({ left, top: rect.bottom + 8, width });
    };

    reposition();
    window.addEventListener("scroll", reposition, true);
    window.addEventListener("resize", reposition);
    return () => {
      window.removeEventListener("scroll", reposition, true);
      window.removeEventListener("resize", reposition);
    };
  }, [showDropdown]);

  useEffect(() => {
    if (!showDropdown) return;
    document
      .getElementById(`${listboxId}-${activeIndex}`)
      ?.scrollIntoView({ block: "nearest" });
  }, [activeIndex, listboxId, showDropdown]);

  const navigateToPlayer = (player: string | null) => {
    trackUmamiEvent("gallery-player-filter-changed", {
      state: player ? "applied" : "cleared",
    });
    setOpen(false);
    startTransition(() => {
      router.push(
        galleryHref({
          season: activeSeason,
          tag: activeTag,
          player,
        }),
        { scroll: false },
      );
    });
  };

  const choosePlayer = (player: GalleryPlayerFilterOption) => {
    setValue(galleryPlayerName(player));
    setSelectedPlayer(player);
    navigateToPlayer(player.username);
  };

  return (
    <form
      className="flex flex-col gap-2 lg:flex-row lg:items-center"
      onSubmit={(event) => {
        event.preventDefault();
        const player = value.trim();
        const playerKey = player.toLocaleLowerCase("en-GB");
        const exactMatch = players.find((option) => {
          return (
            option.username.toLocaleLowerCase("en-GB") === playerKey ||
            galleryPlayerName(option).toLocaleLowerCase("en-GB") === playerKey
          );
        });
        if (exactMatch) {
          choosePlayer(exactMatch);
          return;
        }
        setSelectedPlayer(null);
        navigateToPlayer(player || null);
      }}
    >
      <label
        htmlFor="gallery-player-search"
        className="w-20 shrink-0 text-xs font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400"
      >
        Player
      </label>
      <div className="w-full max-w-md">
        <div
          ref={containerRef}
          className="flex h-10 min-w-0 items-center gap-2 rounded-full bg-paper px-3 py-2 focus-within:ring-2 focus-within:ring-orange-500"
          onBlur={(event) => {
            if (
              !containerRef.current?.contains(
                event.relatedTarget as Node | null,
              ) &&
              !listboxRef.current?.contains(event.relatedTarget as Node | null)
            ) {
              setOpen(false);
            }
          }}
        >
          {selectedPlayer ? (
            <PixelIcon
              src={selectedPlayer.avatarUrl}
              alt=""
              size={24}
              imgClassName="rounded-md"
            />
          ) : null}
          <input
            id="gallery-player-search"
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
            className="min-w-0 flex-1 bg-transparent text-sm text-gray-800 outline-none placeholder:text-gray-400 dark:text-gray-200"
            onFocus={() => {
              setActiveIndex(0);
              setOpen(true);
            }}
            onChange={(event) => {
              const nextValue = event.target.value;
              setValue(nextValue);
              setSelectedPlayer(null);
              setActiveIndex(0);
              setOpen(true);
              if (nextValue === "" && activePlayer !== null) {
                navigateToPlayer(null);
              }
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
        </div>
      </div>
      {showDropdown && listboxPosition
        ? createPortal(
            <div
              ref={listboxRef}
              id={listboxId}
              role="listbox"
              aria-label="Gallery players"
              className="themed-scrollbar fixed max-h-72 overflow-y-auto rounded-2xl border border-line bg-paper-2 p-1.5 shadow-xl"
              style={{
                left: listboxPosition.left,
                top: listboxPosition.top,
                width: listboxPosition.width,
                zIndex: 2_147_483_647,
              }}
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
                  <span className="min-w-0 truncate">
                    {galleryPlayerName(player)}
                  </span>
                </button>
              ))}
            </div>,
            document.body,
          )
        : null}
    </form>
  );
}
