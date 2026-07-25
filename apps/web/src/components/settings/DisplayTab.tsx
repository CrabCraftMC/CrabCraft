"use client";

import { useState, useEffect } from "react";
import { Sun, Moon } from "lucide-react";
import Squircle from "@/components/Squircle";

export default function DisplayTab() {
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    setIsDark(document.documentElement.classList.contains("dark"));
  }, []);

  const toggleDarkMode = () => {
    const newDark = !isDark;
    setIsDark(newDark);
    document.documentElement.classList.toggle("dark", newDark);
    localStorage.setItem("theme", newDark ? "dark" : "light");
  };

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold">Display</h2>

      <Squircle cornerRadius={20} className="bg-[var(--paper-2)] p-5">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-semibold">Dark Mode</p>
            <p className="text-xs opacity-50 mt-0.5">Toggle between light and dark theme</p>
          </div>
          <button
            onClick={toggleDarkMode}
            className="relative w-12 h-7 rounded-full transition-colors cursor-pointer"
            style={{ backgroundColor: isDark ? "var(--foreground)" : "var(--line)" }}
            aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
          >
            <span
              className="absolute top-0.5 flex items-center justify-center w-6 h-6 rounded-full bg-[var(--paper)] shadow-sm transition-transform"
              style={{ transform: isDark ? "translateX(22px)" : "translateX(2px)" }}
            >
              {isDark ? <Moon className="w-3.5 h-3.5" /> : <Sun className="w-3.5 h-3.5" />}
            </span>
          </button>
        </div>
      </Squircle>
    </div>
  );
}
