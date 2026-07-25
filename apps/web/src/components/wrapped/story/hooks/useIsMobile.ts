"use client";

import { useEffect, useState } from "react";

const QUERY = "(max-width: 640px)";

export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(false);

  useEffect(() => {
    const mql = window.matchMedia(QUERY);
    const read = () => setIsMobile(mql.matches);
    read();
    mql.addEventListener("change", read);
    return () => mql.removeEventListener("change", read);
  }, []);

  return isMobile;
}
