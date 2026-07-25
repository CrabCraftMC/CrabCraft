"use client";

import { useEffect, useState } from "react";

export function useIsDark(): boolean {
  const [isDark, setIsDark] = useState(true);

  useEffect(() => {
    const html = document.documentElement;
    const read = () => setIsDark(html.classList.contains("dark"));
    read();
    const obs = new MutationObserver(read);
    obs.observe(html, { attributes: true, attributeFilter: ["class"] });
    return () => obs.disconnect();
  }, []);

  return isDark;
}
