"use client";

import { useEffect, useState } from "react";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import {
  appendPublicChatMessage,
  parsePublicChatEvent,
  type PublicChatMessage,
} from "@/lib/publicChat";

type ConnectionState = "connecting" | "live" | "reconnecting";

const TIME_FORMATTER = new Intl.DateTimeFormat(undefined, {
  hour: "numeric",
  minute: "2-digit",
});

const CONNECTION_LABELS: Record<ConnectionState, string> = {
  connecting: "Connecting",
  live: "Live",
  reconnecting: "Reconnecting",
};

export default function LiveChatSnippet({ streamUrl }: { streamUrl: string }) {
  const [messages, setMessages] = useState<PublicChatMessage[]>([]);
  const [connectionState, setConnectionState] =
    useState<ConnectionState>("connecting");

  useEffect(() => {
    setConnectionState("connecting");

    const source = new EventSource(streamUrl);

    source.onopen = () => setConnectionState("live");
    source.onmessage = (event) => {
      const message = parsePublicChatEvent(event.data, event.lastEventId);
      if (!message) return;

      setMessages((current) => appendPublicChatMessage(current, message));
    };
    source.onerror = () => setConnectionState("reconnecting");

    return () => {
      source.onopen = null;
      source.onmessage = null;
      source.onerror = null;
      source.close();
    };
  }, [streamUrl]);

  const connectionLabel = CONNECTION_LABELS[connectionState];
  const emptyMessage =
    connectionState === "reconnecting"
      ? "Chat is temporarily unavailable. Reconnecting…"
      : connectionState === "live"
        ? "No recent messages yet."
        : "Waiting for the latest messages…";

  return (
    <section className="mt-16 relative" aria-labelledby="live-chat-heading">
      <div className="container mx-auto px-4">
        <div className="mb-8">
          <h2
            id="live-chat-heading"
            className="text-3xl lg:text-4xl font-bold text-orange-500 font-mc"
          >
            Live from CrabCraft
          </h2>
          <p className="mt-2 text-base lg:text-lg text-gray-600 dark:text-gray-400">
            A glimpse of the conversation happening in-game
          </p>
        </div>

        <Squircle
          cornerRadius={32}
          className="overflow-hidden bg-gradient-to-br from-[#29211C] to-[#171311] text-white shadow-xl shadow-black/10"
        >
          <div className="flex flex-col gap-3 border-b border-white/10 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
            <div>
              <p className="font-bold">In-game chat</p>
              <p className="mt-0.5 text-xs text-white/60">
                Most recent public messages
              </p>
            </div>
            <div
              role="status"
              aria-live="polite"
              aria-atomic="true"
              className="flex items-center gap-2 text-sm font-bold text-white/80"
            >
              <span
                aria-hidden="true"
                className={`h-2.5 w-2.5 rounded-full ${
                  connectionState === "live" ? "bg-emerald-400" : "bg-amber-300"
                }`}
              />
              {connectionLabel}
            </div>
          </div>

          {messages.length > 0 ? (
            <ol
              aria-label="Recent in-game chat messages"
              className="divide-y divide-white/10"
            >
              {messages.map((message) => {
                const date = new Date(message.timestamp);

                return (
                  <li
                    key={message.id}
                    className="flex gap-3 px-5 py-3 sm:px-6 sm:py-4"
                  >
                    <PixelIcon
                      src={`https://mc-heads.net/avatar/${encodeURIComponent(message.uuid)}/32.png`}
                      alt=""
                      size={32}
                      imgClassName="rounded"
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-baseline justify-between gap-3">
                        <span className="min-w-0 truncate font-bold text-orange-300">
                          {message.username}
                        </span>
                        <time
                          dateTime={date.toISOString()}
                          title={date.toLocaleString()}
                          className="shrink-0 text-xs text-white/50"
                        >
                          {TIME_FORMATTER.format(date)}
                        </time>
                      </div>
                      <p className="mt-1 break-words text-sm leading-relaxed text-white/90">
                        {message.message}
                      </p>
                    </div>
                  </li>
                );
              })}
            </ol>
          ) : (
            <div className="flex min-h-64 items-center justify-center px-6 text-center text-sm text-white/60">
              <p>{emptyMessage}</p>
            </div>
          )}
        </Squircle>
      </div>
    </section>
  );
}
