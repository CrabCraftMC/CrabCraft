"use client";

import { useState, useEffect } from "react";
import Squircle from "@/components/Squircle";

const TARGET = new Date("2026-07-10T18:00:00Z").getTime();
const REVEAL = new Date("2026-05-04T00:00:00Z").getTime();

function calcTimeLeft() {
  const diff = TARGET - Date.now();
  if (diff <= 0) return null;
  return {
    days: Math.floor(diff / 86400000),
    hours: Math.floor((diff / 3600000) % 24),
    minutes: Math.floor((diff / 60000) % 60),
    seconds: Math.floor((diff / 1000) % 60),
  };
}

const UNITS = ["Days", "Hours", "Minutes", "Seconds"] as const;

export default function CountdownBanner() {
  const [timeLeft, setTimeLeft] = useState<
    ReturnType<typeof calcTimeLeft> | undefined
  >(undefined);

  useEffect(() => {
    const tick = () => setTimeLeft(calcTimeLeft());
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, []);

  const isLive = timeLeft === null;
  const isHydrated = timeLeft !== undefined;

  if (isHydrated && Date.now() < REVEAL) return null;

  const values = isHydrated && !isLive
    ? [timeLeft.days, timeLeft.hours, timeLeft.minutes, timeLeft.seconds]
    : [null, null, null, null];

  return (
    <section className="mt-16 relative">
      <div className="container mx-auto px-4">
        <Squircle
          cornerRadius={24}
          className="card-hover animate-in py-3 px-4 sm:py-4 sm:px-6 relative overflow-hidden bg-gradient-to-r from-[#D97706] to-[#FBBF24]"
          style={{ animationDelay: "0.05s" }}
        >
          <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[30px] sm:text-[45px] lg:text-[60px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
            SEASON 7
          </span>

          <div className="relative z-10 flex flex-col sm:flex-row items-center justify-between gap-2 sm:gap-4">
            {isLive ? (
              <h2 className="text-lg sm:text-xl font-bold text-white font-mc">
                Season 7 is Live!
              </h2>
            ) : (
              <>
                <div className="flex items-center gap-3">
                  <h2 className="text-base sm:text-lg lg:text-xl font-bold text-white font-mc whitespace-nowrap">
                    Season 7
                  </h2>
                  {isHydrated && (
                    <span className="text-white/50 text-xs sm:text-sm hidden sm:inline">
                      {new Date(TARGET).toLocaleDateString(undefined, {
                        month: "long",
                        day: "numeric",
                      })}{" — "}
                      {new Date(TARGET).toLocaleTimeString(undefined, {
                        hour: "numeric",
                        minute: "2-digit",
                        timeZoneName: "short",
                      })}
                    </span>
                  )}
                </div>

                <div className="flex items-center gap-3 sm:gap-4 lg:gap-6">
                  <div className="flex items-center gap-2 sm:gap-3 lg:gap-4">
                    {UNITS.map((label, i) => (
                      <div key={label} className="flex items-center gap-2 sm:gap-3 lg:gap-4">
                        <div className="flex flex-col items-center min-w-[2.2rem] sm:min-w-[3rem]">
                          <span className="text-xl sm:text-2xl lg:text-3xl font-bold text-white font-mc">
                            {values[i] !== null
                              ? String(values[i]).padStart(2, "0")
                              : "--"}
                          </span>
                          <span className="text-white/60 text-[8px] sm:text-[10px] uppercase tracking-wider">
                            {label}
                          </span>
                        </div>
                        {i < UNITS.length - 1 && (
                          <span className="text-xl sm:text-2xl lg:text-3xl font-bold text-white/30 font-mc">
                            :
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                  <a
                    href="https://discord.crabcraft.net"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="bg-white/20 backdrop-blur-sm border border-white/30 text-white font-bold py-1.5 px-4 sm:py-2 sm:px-5 rounded-full text-xs sm:text-sm shadow-lg transition-transform hover:scale-105 whitespace-nowrap"
                  >
                    Apply Now
                  </a>
                </div>
              </>
            )}
          </div>
        </Squircle>
      </div>
    </section>
  );
}
