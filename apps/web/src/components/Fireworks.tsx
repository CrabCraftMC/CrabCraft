"use client";

import dynamic from "next/dynamic";

export default dynamic(
  () => import("@fireworks-js/react").then(({ Fireworks }) => Fireworks),
  { ssr: false, loading: () => null },
);
