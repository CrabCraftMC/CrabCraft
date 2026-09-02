"use client";

import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { createPortal } from "react-dom";
import { motion, useReducedMotion } from "framer-motion";
import Link from "next/link";
import {
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Play,
  Search,
  Share2,
  SkipForward,
  X,
} from "lucide-react";
import Squircle from "@/components/Squircle";
import {
  getHuntDailyNumber,
  getHuntDailyPuzzle,
  HUNT_CLUES,
  HUNT_CONFIG,
  HUNT_KINDS,
} from "@/lib/hunt";
import {
  getHuntCatalogue,
  getHuntEntry,
  HUNT_ATLAS_CELL_SIZE,
  HUNT_ATLAS_COLUMNS,
  normaliseHuntGuess,
  searchHuntEntries,
  type HuntEntry,
  type HuntKind,
} from "@/lib/huntCatalogue";
import { parseBlockHuntGlossary } from "@/lib/blockHuntGlossary";
import { formatHuntShare } from "@/lib/blockHuntShare";
import { trackUmamiEvent } from "@/lib/umami";

type GamePhase = "playing" | "won" | "lost";

type SavedGame = {
  cluesRevealed: number;
  guesses: string[];
  phase: GamePhase;
  startedAt: number | null;
  elapsedMs: number | null;
  timerEnabled: boolean;
};

const INITIAL_GAME: SavedGame = {
  cluesRevealed: 1,
  guesses: [],
  phase: "playing",
  startedAt: null,
  elapsedMs: null,
  timerEnabled: true,
};

const HUNT_STORAGE_VERSION = 5;

function readSavedGame(key: string): SavedGame {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? "null");
    if (
      saved &&
      Number.isInteger(saved.cluesRevealed) &&
      saved.cluesRevealed >= 1 &&
      saved.cluesRevealed <= HUNT_CLUES &&
      Array.isArray(saved.guesses) &&
      saved.guesses.every((guess: unknown) => typeof guess === "string") &&
      saved.guesses.length <= HUNT_CLUES &&
      ["playing", "won", "lost"].includes(saved.phase) &&
      (saved.startedAt === null ||
        (typeof saved.startedAt === "number" &&
          Number.isFinite(saved.startedAt))) &&
      (saved.elapsedMs === null ||
        (typeof saved.elapsedMs === "number" &&
          Number.isFinite(saved.elapsedMs) &&
          saved.elapsedMs >= 0)) &&
      (saved.timerEnabled === undefined ||
        typeof saved.timerEnabled === "boolean")
    ) {
      return {
        ...saved,
        timerEnabled: saved.timerEnabled ?? true,
      };
    }
  } catch {
    // Ignore damaged local state and start a fresh game.
  }
  return INITIAL_GAME;
}

function formatGuessCount(count: number): string {
  return `${count} ${count === 1 ? "guess" : "guesses"}`;
}

function formatDuration(milliseconds: number): string {
  const totalSeconds = Math.floor(milliseconds / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function HuntPreview({
  entry,
  kind,
  size = HUNT_ATLAS_CELL_SIZE,
}: {
  entry: HuntEntry;
  kind: HuntKind;
  size?: number;
}) {
  const column = entry.sprite % HUNT_ATLAS_COLUMNS;
  const row = Math.floor(entry.sprite / HUNT_ATLAS_COLUMNS);
  const scale = size / HUNT_ATLAS_CELL_SIZE;

  return (
    <span
      aria-hidden="true"
      className="inline-block shrink-0"
      style={{
        width: size,
        height: size,
        backgroundImage: `url(/textures/hunt/${kind}s.webp)`,
        backgroundPosition: `${-column * HUNT_ATLAS_CELL_SIZE * scale}px ${-row * HUNT_ATLAS_CELL_SIZE * scale}px`,
        backgroundSize: `${HUNT_ATLAS_COLUMNS * HUNT_ATLAS_CELL_SIZE * scale}px auto`,
        backgroundRepeat: "no-repeat",
        imageRendering: "pixelated",
      }}
    />
  );
}

type TooltipPosition = {
  left: number;
  top: number;
  placement: "above" | "below";
};

function GlossaryTerm({
  text,
  definition,
  tooltipId,
}: {
  text: string;
  definition: string;
  tooltipId: string;
}) {
  const [position, setPosition] = useState<TooltipPosition | null>(null);

  const showTooltip = (element: HTMLButtonElement) => {
    const bounds = element.getBoundingClientRect();
    const tooltipWidth = Math.min(208, window.innerWidth - 24);
    const halfWidth = tooltipWidth / 2;
    const placement = bounds.top < 104 ? "below" : "above";

    setPosition({
      left: Math.min(
        window.innerWidth - halfWidth - 12,
        Math.max(halfWidth + 12, bounds.left + bounds.width / 2),
      ),
      top: placement === "above" ? bounds.top - 10 : bounds.bottom + 10,
      placement,
    });
  };

  return (
    <span className="inline">
      <button
        type="button"
        aria-describedby={position ? tooltipId : undefined}
        onMouseEnter={(event) => showTooltip(event.currentTarget)}
        onMouseLeave={() => setPosition(null)}
        onFocus={(event) => showTooltip(event.currentTarget)}
        onBlur={() => setPosition(null)}
        className="cursor-pointer bg-transparent p-0 font-[inherit] text-[inherit] underline decoration-gray-500 decoration-dotted decoration-1 underline-offset-4 focus-visible:outline-none focus-visible:decoration-orange-500 dark:decoration-gray-400"
      >
        {text}
      </button>
      {position &&
        createPortal(
          <span
            id={tooltipId}
            role="tooltip"
            style={{ left: position.left, top: position.top }}
            className={`pointer-events-none fixed z-[100] w-52 max-w-[calc(100vw-1.5rem)] -translate-x-1/2 rounded-lg bg-gray-950 px-3 py-2 text-left text-[11px] font-medium leading-4 text-white shadow-lg dark:bg-gray-50 dark:text-gray-900 ${
              position.placement === "above" ? "-translate-y-full" : ""
            }`}
          >
            {definition}
            <span
              aria-hidden="true"
              className={`absolute left-1/2 h-2 w-2 -translate-x-1/2 rotate-45 bg-gray-950 dark:bg-gray-50 ${
                position.placement === "above"
                  ? "top-full -translate-y-1/2"
                  : "bottom-full translate-y-1/2"
              }`}
            />
          </span>,
          document.body,
        )}
    </span>
  );
}

function ClueText({ text, idPrefix }: { text: string; idPrefix: string }) {
  return (
    <>
      {parseBlockHuntGlossary(text).map((part, index) => {
        if (!part.definition) return part.text;

        const tooltipId = `${idPrefix}-${index}`;
        return (
          <GlossaryTerm
            key={tooltipId}
            text={part.text}
            definition={part.definition}
            tooltipId={tooltipId}
          />
        );
      })}
    </>
  );
}

export default function BlockHunt({ kind = "block" }: { kind?: HuntKind }) {
  const config = HUNT_CONFIG[kind];
  const catalogue = getHuntCatalogue(kind);
  const dailyNumber = getHuntDailyNumber();
  const puzzle = getHuntDailyPuzzle(kind);
  const puzzleKey = `crabcraft-${kind}-hunt-v${HUNT_STORAGE_VERSION}:daily:${dailyNumber}`;

  const [game, setGame] = useState<SavedGame>(INITIAL_GAME);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [guess, setGuess] = useState("");
  const [message, setMessage] = useState("");
  const [shareStatus, setShareStatus] = useState<
    "idle" | "copying" | "copied"
  >("idle");
  const [shareError, setShareError] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [activeOption, setActiveOption] = useState(0);
  const [clueIndex, setClueIndex] = useState(0);
  const [clockNow, setClockNow] = useState(() => Date.now());
  const [huntMenuOpen, setHuntMenuOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const searchRef = useRef<HTMLDivElement>(null);
  const huntMenuRef = useRef<HTMLSpanElement>(null);
  const prefersReducedMotion = useReducedMotion();

  const gameReady = loadedKey === puzzleKey;
  const finished = game.phase !== "playing";
  const started = game.startedAt !== null;
  const availableClues = finished ? HUNT_CLUES : game.cluesRevealed;
  const attemptCount =
    game.guesses.length + (game.phase === "won" ? 1 : 0);
  const guessesLeft = Math.max(0, HUNT_CLUES - game.guesses.length);
  const elapsedMs = game.timerEnabled
    ? game.elapsedMs ??
      (game.startedAt === null ? 0 : Math.max(0, clockNow - game.startedAt))
    : 0;
  const currentClue = puzzle.clues[clueIndex];
  const selectedBlock = getHuntEntry(kind, guess);
  const answerEntry = getHuntEntry(kind, puzzle.answer);

  const filteredBlocks = useMemo(
    () => searchHuntEntries(kind, guess),
    [guess, kind],
  );

  useEffect(() => {
    const saved = readSavedGame(puzzleKey);
    setGame(saved);
    setGuess("");
    setMessage("");
    setShareStatus("idle");
    setShareError("");
    setSearchOpen(false);
    setClockNow(Date.now());
    setLoadedKey(puzzleKey);
  }, [puzzleKey]);

  useEffect(() => {
    if (loadedKey !== puzzleKey) return;
    localStorage.setItem(puzzleKey, JSON.stringify(game));
  }, [game, loadedKey, puzzleKey]);

  useEffect(() => {
    setClueIndex(Math.max(0, availableClues - 1));
  }, [availableClues, puzzleKey]);

  useEffect(() => {
    setHuntMenuOpen(false);
  }, [kind]);

  useEffect(() => {
    if (!started || finished || !game.timerEnabled) return;

    setClockNow(Date.now());
    const timer = window.setInterval(() => setClockNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [finished, game.timerEnabled, started]);

  useEffect(() => {
    const closeSearch = (event: MouseEvent) => {
      if (!searchRef.current?.contains(event.target as Node)) {
        setSearchOpen(false);
      }
      if (!huntMenuRef.current?.contains(event.target as Node)) {
        setHuntMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", closeSearch);
    return () => document.removeEventListener("mousedown", closeSearch);
  }, []);

  const focusGuess = () => {
    requestAnimationFrame(() => inputRef.current?.focus());
  };

  const startGame = () => {
    if (started || finished) return;

    const startedAt = Date.now();
    setClockNow(startedAt);
    setGame((current) => ({ ...current, startedAt, elapsedMs: null }));
    trackUmamiEvent(`${kind}-hunt-started`, {
      mode: "daily",
      timed: game.timerEnabled,
    });
    focusGuess();
  };

  const selectBlock = (block: HuntEntry) => {
    setGuess(block.name);
    setSearchOpen(false);
    setActiveOption(0);
    setMessage("");
    focusGuess();
  };

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (!searchOpen || filteredBlocks.length === 0) {
      if (event.key === "ArrowDown" && filteredBlocks.length > 0) {
        event.preventDefault();
        setSearchOpen(true);
      }
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveOption((current) => (current + 1) % filteredBlocks.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveOption(
        (current) =>
          (current - 1 + filteredBlocks.length) % filteredBlocks.length,
      );
    } else if (event.key === "Enter") {
      event.preventDefault();
      selectBlock(filteredBlocks[activeOption] ?? filteredBlocks[0]);
    } else if (event.key === "Escape") {
      setSearchOpen(false);
    }
  };

  const submitGuess = (event: FormEvent) => {
    event.preventDefault();
    if (game.phase !== "playing") return;

    const normalisedGuess = normaliseHuntGuess(guess);
    const canonicalGuess = getHuntEntry(kind, normalisedGuess);

    if (!canonicalGuess) {
      setMessage(`Choose a Minecraft ${config.singular} from the list.`);
      setSearchOpen(true);
      focusGuess();
      return;
    }

    if (
      game.guesses.some(
        (previous) => getHuntEntry(kind, previous)?.id === canonicalGuess.id,
      )
    ) {
      setMessage(`You already tried that ${config.singular}. Choose another one.`);
      focusGuess();
      return;
    }

    const answerBlock = getHuntEntry(kind, puzzle.answer);
    if (
      answerBlock
        ? canonicalGuess.id === answerBlock.id
        : normalisedGuess === normaliseHuntGuess(puzzle.answer)
    ) {
      const finalElapsedMs =
        game.timerEnabled && game.startedAt !== null
          ? Math.max(0, Date.now() - game.startedAt)
          : 0;
      setGame((current) => ({
        ...current,
        phase: "won",
        elapsedMs: finalElapsedMs,
      }));
      setGuess("");
      setMessage("");
      setSearchOpen(false);
      trackUmamiEvent(`${kind}-hunt-completed`, {
        mode: "daily",
        guesses: game.guesses.length + 1,
        clues: game.cluesRevealed,
        timed: game.timerEnabled,
        ...(game.timerEnabled
          ? { seconds: Math.floor(finalElapsedMs / 1000) }
          : {}),
      });
      return;
    }

    const nextGuesses = [...game.guesses, canonicalGuess.name];
    const lost = nextGuesses.length >= HUNT_CLUES;
    const nextCluesRevealed = Math.min(
      HUNT_CLUES,
      game.cluesRevealed + 1,
    );
    const finalElapsedMs =
      lost && game.timerEnabled && game.startedAt !== null
        ? Math.max(0, Date.now() - game.startedAt)
        : lost
          ? 0
          : null;
    setGame((current) => ({
      ...current,
      cluesRevealed: nextCluesRevealed,
      guesses: nextGuesses,
      phase: lost ? "lost" : "playing",
      elapsedMs: finalElapsedMs,
    }));
    setGuess("");
    setSearchOpen(false);
    setMessage(
      lost
        ? `That was the final guess. The ${config.singular} has been revealed.`
        : `Not that ${config.singular}. A new clue is available.`,
    );
    trackUmamiEvent(`${kind}-hunt-guess`, {
      mode: "daily",
      correct: false,
      guess: nextGuesses.length,
      clue: nextCluesRevealed,
    });
    if (lost) {
      trackUmamiEvent(`${kind}-hunt-failed`, {
        mode: "daily",
        guesses: nextGuesses.length,
        clues: nextCluesRevealed,
        timed: game.timerEnabled,
        ...(game.timerEnabled
          ? { seconds: Math.floor((finalElapsedMs ?? 0) / 1000) }
          : {}),
      });
    }
    if (!lost) focusGuess();
  };

  const revealNextClue = () => {
    if (
      game.phase !== "playing" ||
      game.cluesRevealed >= HUNT_CLUES
    ) {
      return;
    }
    setGame((current) => ({
      ...current,
      cluesRevealed: current.cluesRevealed + 1,
    }));
    setMessage("Clue revealed.");
    trackUmamiEvent(`${kind}-hunt-clue-skipped`, {
      mode: "daily",
      clue: game.cluesRevealed + 1,
    });
  };

  const shareResult = async () => {
    setShareStatus("copying");
    setShareError("");

    try {
      await navigator.clipboard.writeText(
        formatHuntShare({
          kind,
          dailyNumber,
          phase: game.phase === "won" ? "won" : "lost",
          attemptCount,
          cluesRevealed: game.cluesRevealed,
          elapsedMs: game.timerEnabled ? elapsedMs : null,
        }),
      );
      setShareStatus("copied");
      window.setTimeout(() => setShareStatus("idle"), 1800);
      trackUmamiEvent(`${kind}-hunt-result-shared`, {
        mode: "daily",
        guesses: attemptCount,
        timed: game.timerEnabled,
        ...(game.timerEnabled
          ? { seconds: Math.floor(elapsedMs / 1000) }
          : {}),
      });
    } catch {
      setShareStatus("idle");
      setShareError("Could not copy the result. Please try again.");
    }
  };

  const messageIsError = Boolean(message) && message !== "Clue revealed.";

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto max-w-4xl px-4">
        <div className="relative z-40 mb-10 text-center animate-in">
          <h1
            aria-label={config.name}
            className="flex items-center justify-center gap-2 text-4xl font-bold text-orange-500 font-mc lg:text-5xl"
          >
            <span ref={huntMenuRef} className="relative inline-flex">
              <button
                type="button"
                aria-label={`Choose hunt type, currently ${config.name}`}
                aria-haspopup="menu"
                aria-expanded={huntMenuOpen}
                onClick={() => setHuntMenuOpen((open) => !open)}
                className="inline-flex cursor-pointer items-center gap-1.5 transition-colors hover:text-orange-600"
              >
                <img
                  src={config.icon}
                  alt=""
                  className="h-8 w-8 object-contain [image-rendering:pixelated] lg:h-10 lg:w-10"
                />
                <span>{config.name.replace(" Hunt", "")}</span>
                <ChevronDown
                  className={`h-5 w-5 transition-transform ${huntMenuOpen ? "rotate-180" : ""}`}
                />
              </button>
              {huntMenuOpen && (
                <span className="absolute left-1/2 top-full z-30 mt-3 w-44 -translate-x-1/2 rounded-xl border border-line bg-paper-2 p-1.5 text-left font-sans text-sm shadow-xl">
                  {HUNT_KINDS.map((huntKind) => {
                    const option = HUNT_CONFIG[huntKind];
                    return (
                      <Link
                        key={huntKind}
                        href={option.route}
                        className={`flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 font-bold transition-colors ${
                          huntKind === kind
                            ? "bg-orange-500/10 text-orange-500"
                            : "text-gray-700 hover:bg-paper dark:text-gray-200"
                        }`}
                      >
                        <img
                          src={option.icon}
                          alt=""
                          className="h-7 w-7 object-contain [image-rendering:pixelated]"
                        />
                        {option.name.replace(" Hunt", "")}
                      </Link>
                    );
                  })}
                </span>
              )}
            </span>
            <span>Hunt</span>
          </h1>
        </div>

        <div className="mx-auto max-w-2xl">
          <div className="mb-4 flex items-center justify-between text-xs font-bold text-gray-400">
            <span>Daily #{dailyNumber}</span>
            {gameReady && (
              <span className="flex items-center gap-2">
                <span>{formatGuessCount(attemptCount)}</span>
                {game.timerEnabled && (
                  <>
                    <span aria-hidden="true">·</span>
                    <time className="tabular-nums">
                      {formatDuration(elapsedMs)}
                    </time>
                  </>
                )}
              </span>
            )}
          </div>

          <Squircle
            cornerRadius={24}
            className="bg-paper-2 px-6 py-8 shadow-sm sm:px-10 sm:py-10"
          >
            {!gameReady ? (
              <div aria-hidden="true" className="min-h-48" />
            ) : !started ? (
              <motion.div
                initial={prefersReducedMotion ? false : { opacity: 0, scale: 0.96 }}
                animate={{ opacity: 1, scale: 1 }}
                className="relative flex min-h-48 flex-col items-center justify-center text-center"
              >
                <button
                  type="button"
                  onClick={startGame}
                  aria-label={
                    game.timerEnabled ? "Start timed game" : "Start game"
                  }
                  className="flex h-16 w-16 cursor-pointer items-center justify-center rounded-full bg-orange-500 text-white shadow-sm transition-transform hover:scale-105 hover:bg-orange-600 active:scale-95"
                >
                  <Play className="ml-1 h-7 w-7 fill-current" />
                </button>
                <p className="mt-3 text-sm font-bold text-gray-600 dark:text-gray-300">
                  Start
                </p>
                <button
                  type="button"
                  role="switch"
                  aria-checked={game.timerEnabled}
                  aria-label={`${game.timerEnabled ? "Turn off" : "Turn on"} timer`}
                  onClick={() =>
                    setGame((current) => ({
                      ...current,
                      timerEnabled: !current.timerEnabled,
                    }))
                  }
                  className="absolute bottom-0 right-0 flex cursor-pointer items-center gap-2 text-xs font-bold text-gray-500 transition-colors hover:text-orange-500 dark:text-gray-400"
                >
                  <span>Timer</span>
                  <span
                    aria-hidden="true"
                    className={`relative h-5 w-9 rounded-full transition-colors ${
                      game.timerEnabled
                        ? "bg-orange-500"
                        : "bg-gray-300 dark:bg-gray-600"
                    }`}
                  >
                    <span
                      className={`absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-transform ${
                        game.timerEnabled
                          ? "translate-x-4"
                          : "translate-x-0"
                      }`}
                    />
                  </span>
                </button>
              </motion.div>
            ) : (
              <>
                <motion.div
                  key={`${puzzle.answer}-${clueIndex}`}
                  initial={prefersReducedMotion ? false : { opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="text-center"
                >
                  <p className="text-xs font-bold uppercase tracking-wide text-orange-500">
                    {currentClue.label}
                  </p>
                  <p className="mx-auto mt-6 max-w-xl text-lg font-semibold leading-8 text-gray-800 dark:text-gray-100 sm:text-xl">
                    <ClueText
                      text={currentClue.text}
                      idPrefix={`${kind}-hunt-tooltip-${dailyNumber}-${clueIndex}`}
                    />
                  </p>
                </motion.div>

                <div className="mt-8 grid grid-cols-[1fr_auto_1fr] items-center border-t border-line pt-4">
                  <button
                    type="button"
                    onClick={() =>
                      setClueIndex((current) => Math.max(0, current - 1))
                    }
                    disabled={clueIndex === 0}
                    aria-label="Previous clue"
                    className="flex cursor-pointer items-center gap-1 justify-self-start text-xs font-bold text-gray-500 transition-colors hover:text-orange-500 disabled:cursor-default disabled:opacity-25 dark:text-gray-400"
                  >
                    <ChevronLeft className="h-4 w-4" /> Previous
                  </button>
                  <span className="text-center text-xs font-bold text-gray-400">
                    Clue {clueIndex + 1} of {HUNT_CLUES}
                  </span>
                  <button
                    type="button"
                    onClick={() =>
                      setClueIndex((current) =>
                        Math.min(availableClues - 1, current + 1),
                      )
                    }
                    disabled={clueIndex >= availableClues - 1}
                    aria-label="Next clue"
                    className="flex cursor-pointer items-center gap-1 justify-self-end text-xs font-bold text-gray-500 transition-colors hover:text-orange-500 disabled:cursor-default disabled:opacity-25 dark:text-gray-400"
                  >
                    Next <ChevronRight className="h-4 w-4" />
                  </button>
                </div>
              </>
            )}
          </Squircle>

          {gameReady && started && (!finished ? (
            <form onSubmit={submitGuess} className="mt-8">
              <label
                htmlFor={`${kind}-hunt-guess`}
                className="mb-2 block text-sm font-bold text-gray-700 dark:text-gray-300"
              >
                Guess the {config.singular}
              </label>
              <div className="flex flex-col gap-2 sm:flex-row">
                <div ref={searchRef} className="relative min-w-0 flex-1">
                  <div className="relative">
                    {selectedBlock ? (
                      <span className="pointer-events-none absolute left-2.5 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center overflow-hidden rounded-md">
                        <HuntPreview entry={selectedBlock} kind={kind} />
                      </span>
                    ) : (
                      <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
                    )}
                    <input
                      ref={inputRef}
                      id={`${kind}-hunt-guess`}
                      role="combobox"
                      aria-expanded={searchOpen}
                      aria-controls={`${kind}-hunt-options`}
                      aria-autocomplete="list"
                      aria-activedescendant={
                        searchOpen && filteredBlocks[activeOption]
                          ? `${kind}-hunt-option-${filteredBlocks[activeOption].id}`
                          : undefined
                      }
                      value={guess}
                      onFocus={() => setSearchOpen(Boolean(guess.trim()))}
                      onChange={(event) => {
                        setGuess(event.target.value);
                        setSearchOpen(true);
                        setActiveOption(0);
                        setMessage("");
                      }}
                      onKeyDown={handleSearchKeyDown}
                      autoComplete="off"
                      autoCapitalize="words"
                      placeholder={`Search ${config.plural}...`}
                      className="h-12 w-full rounded-xl border border-line bg-paper pl-12 pr-4 text-sm font-semibold text-gray-800 outline-none transition-colors focus:border-orange-400 dark:text-gray-100"
                    />
                  </div>

                  {searchOpen && guess.trim() && (
                    <div
                      id={`${kind}-hunt-options`}
                      role="listbox"
                      className="absolute left-0 right-0 top-full z-20 mt-2 max-h-80 overflow-y-auto rounded-xl border border-line bg-paper-2 p-1.5 shadow-xl themed-scrollbar"
                    >
                      {filteredBlocks.map((block, index) => (
                        <button
                          id={`${kind}-hunt-option-${block.id}`}
                          key={block.id}
                          type="button"
                          role="option"
                          aria-selected={index === activeOption}
                          onMouseDown={(event) => event.preventDefault()}
                          onClick={() => selectBlock(block)}
                          onMouseEnter={() => setActiveOption(index)}
                          className={`flex w-full cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2 text-left transition-colors ${
                            index === activeOption ? "bg-paper" : "hover:bg-paper"
                          }`}
                        >
                          <span className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-md">
                            <HuntPreview entry={block} kind={kind} />
                          </span>
                          <span className="min-w-0 truncate text-sm font-bold text-gray-700 dark:text-gray-200">
                            {block.name}
                          </span>
                        </button>
                      ))}
                      {filteredBlocks.length === 0 && (
                        <p className="px-3 py-4 text-center text-sm text-gray-400">
                          No {config.plural} found
                        </p>
                      )}
                    </div>
                  )}
                </div>

                <button
                  type="submit"
                  className="h-12 cursor-pointer rounded-xl bg-orange-500 px-6 text-sm font-bold text-white transition-colors hover:bg-orange-600"
                >
                  Guess
                </button>
              </div>

              <div className="mt-3 flex min-h-8 flex-wrap items-center justify-between gap-3">
                <p
                  aria-live="polite"
                  className={`text-xs font-semibold ${
                    messageIsError
                      ? "text-red-500"
                      : "text-gray-400 dark:text-gray-500"
                  }`}
                >
                  {message || `${guessesLeft} guesses left`}
                </p>
                {game.cluesRevealed < HUNT_CLUES && (
                  <button
                    type="button"
                    onClick={revealNextClue}
                    className="inline-flex cursor-pointer items-center gap-1.5 text-xs font-bold text-gray-400 transition-colors hover:text-orange-500"
                  >
                    <SkipForward className="h-3.5 w-3.5" />
                    Reveal next clue
                  </button>
                )}
              </div>
            </form>
          ) : (
            <div className="mt-8 text-center">
              {answerEntry && (
                <span
                  role="img"
                  aria-label={puzzle.answer}
                  className="mx-auto flex h-24 w-24 items-center justify-center"
                >
                  <HuntPreview entry={answerEntry} kind={kind} size={96} />
                </span>
              )}
              <p
                className={`mt-4 text-sm font-bold ${
                  game.phase === "won" ? "text-emerald-600" : "text-red-500"
                }`}
              >
                {game.phase === "won" ? "You found it" : "The answer was"}
              </p>
              <p className="mt-1 text-2xl font-bold text-gray-800 dark:text-gray-100">
                {puzzle.answer}
              </p>
              <p className="mt-3 text-sm font-semibold text-gray-500 dark:text-gray-400">
                {game.phase === "won"
                  ? `Solved in ${formatGuessCount(attemptCount)}`
                  : formatGuessCount(attemptCount)}
              </p>
              {game.timerEnabled && (
                <p className="mt-1 text-lg font-bold tabular-nums text-gray-800 dark:text-gray-100">
                  Time {formatDuration(elapsedMs)}
                </p>
              )}
              <button
                type="button"
                onClick={shareResult}
                disabled={shareStatus === "copying"}
                className="mt-6 inline-flex h-11 cursor-pointer items-center justify-center gap-2 rounded-xl border border-line bg-paper px-5 text-xs font-bold text-gray-700 transition-colors hover:text-orange-500 disabled:cursor-wait disabled:opacity-50 dark:text-gray-200"
              >
                {shareStatus === "copied" ? (
                  <Check className="h-4 w-4" />
                ) : (
                  <Share2 className="h-4 w-4" />
                )}
                {shareStatus === "copying"
                  ? "Copying"
                  : shareStatus === "copied"
                    ? "Copied"
                    : "Share result"}
              </button>
              {shareError && (
                <p
                  aria-live="polite"
                  className="mt-3 text-xs font-semibold text-red-500"
                >
                  {shareError}
                </p>
              )}
            </div>
          ))}

          {game.guesses.length > 0 && (
            <div className="mt-6 flex flex-wrap items-center justify-center gap-x-4 gap-y-2 text-xs text-gray-400">
              <span className="font-bold">Tried</span>
              {game.guesses.map((previousGuess) => (
                <span key={previousGuess} className="inline-flex items-center gap-1">
                  <X className="h-3 w-3 text-red-400" /> {previousGuess}
                </span>
              ))}
            </div>
          )}

          <p className="mt-12 text-center text-[11px] text-gray-400">
            Java Edition {catalogue.version} data and images from{" "}
            <a
              href={catalogue.source}
              target="_blank"
              rel="noopener noreferrer"
              className="cursor-pointer underline underline-offset-2 hover:text-orange-500"
            >
              Minecraft Wiki
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
