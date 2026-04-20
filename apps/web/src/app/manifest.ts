import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "CrabCraft",
    short_name: "CrabCraft",
    description:
      "CrabCraft is a whitelisted Minecraft survival server. Apply to join and start your adventure.",
    start_url: "/",
    display: "standalone",
    background_color: "#1a1412",
    theme_color: "#f97316",
    icons: [
      { src: "/logo.png", sizes: "512x512", type: "image/png" },
      { src: "/apple-touch-icon.png", sizes: "180x180", type: "image/png" },
    ],
  };
}
