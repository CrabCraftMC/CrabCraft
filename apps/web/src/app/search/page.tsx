"use client";

import { useState, useEffect, useRef } from "react";
import Link from "next/link";
import Image from "next/image";
import type { Metadata } from "next";
import { Search } from "lucide-react";
import Squircle from "@/components/Squircle";

interface PlayerResult {
  minecraft_uuid: string;
  minecraft_username: string;
}

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<PlayerResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    if (query.length < 2) {
      setResults([]);
      setSearched(false);
      return;
    }

    const timeout = setTimeout(async () => {
      setLoading(true);
      try {
        const res = await fetch(`/api/players/search?q=${encodeURIComponent(query)}&limit=15`);
        if (res.ok) {
          const data = await res.json();
          setResults(data);
        }
      } catch {
        setResults([]);
      } finally {
        setLoading(false);
        setSearched(true);
      }
    }, 300);

    return () => clearTimeout(timeout);
  }, [query]);

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-2xl">
        <div className="text-center mb-8 animate-in">
          <h1 className="text-3xl font-bold text-foreground">Search Players</h1>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-2">
            Find a player to view their stats and advancements
          </p>
        </div>

        <Squircle
          cornerRadius={24}
          className="bg-paper-2 p-2 flex items-center gap-3 animate-in"
          style={{ animationDelay: "0.1s" }}
        >
          <Search className="w-5 h-5 text-gray-400 ml-4 shrink-0" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Enter a Minecraft username..."
            className="flex-1 py-3 text-base bg-transparent text-foreground placeholder-gray-400 dark:placeholder-gray-500 outline-none"
          />
        </Squircle>

        <div className="mt-6 animate-in" style={{ animationDelay: "0.15s" }}>
          {loading && (
            <p className="text-center text-sm text-gray-400">Searching...</p>
          )}

          {!loading && results.length > 0 && (
            <Squircle cornerRadius={24} className="bg-paper-2 overflow-hidden">
              {results.map((player, i) => (
                <Link
                  key={player.minecraft_uuid}
                  href={`/stats/${player.minecraft_uuid}`}
                  className={`flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-orange-50/60 dark:hover:bg-[#2a221b] ${
                    i % 2 === 0
                      ? "bg-paper-2"
                      : "bg-paper/60 dark:bg-[#2a221b]/40"
                  }`}
                >
                  <Image
                    src={`https://mc-heads.net/avatar/${player.minecraft_uuid}/28`}
                    alt={player.minecraft_username}
                    width={28}
                    height={28}
                    className="rounded shrink-0"
                    unoptimized
                  />
                  <span className="text-foreground text-sm font-medium">
                    {player.minecraft_username}
                  </span>
                </Link>
              ))}
            </Squircle>
          )}

          {!loading && searched && results.length === 0 && query.length >= 2 && (
            <p className="text-center text-sm text-gray-400">
              No players found for &ldquo;{query}&rdquo;
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
