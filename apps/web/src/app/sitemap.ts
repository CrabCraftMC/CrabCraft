import type { MetadataRoute } from "next";
import { getGallerySitemapEntries } from "@crabcraft/db/queries/web";
import { searchUsers } from "@/lib/queries";

export const revalidate = 3600;

const BASE_URL = "https://crabcraft.net";
const MAX_GALLERY_SITEMAP_ENTRIES = 39_000;

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const staticRoutes: MetadataRoute.Sitemap = [
    { url: BASE_URL, changeFrequency: "daily", priority: 1 },
    { url: `${BASE_URL}/leaderboard`, changeFrequency: "hourly", priority: 0.9 },
    { url: `${BASE_URL}/awards`, changeFrequency: "daily", priority: 0.8 },
    { url: `${BASE_URL}/gallery`, changeFrequency: "daily", priority: 0.8 },
    { url: `${BASE_URL}/games/block-hunt`, changeFrequency: "daily", priority: 0.7 },
    { url: `${BASE_URL}/tools/rgb-nickname`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/block-gradient`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/pixel-art-generator`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/circle-generator`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/stack-calculator`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/beacon-calculator`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/xp-calculator`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/enchantment-planner`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/portal-calculator`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/wrapped`, changeFrequency: "weekly", priority: 0.7 },
  ];

  let playerRoutes: MetadataRoute.Sitemap = [];
  try {
    const rows = await searchUsers("", 10000);
    playerRoutes = rows.map((row) => ({
      url: `${BASE_URL}/stats/${row.minecraft_uuid}`,
      changeFrequency: "daily" as const,
      priority: 0.5,
    }));
  } catch {}

  let awardRoutes: MetadataRoute.Sitemap = [];
  try {
    const res = await fetch("https://api.crabcraft.net/awards", {
      signal: AbortSignal.timeout(10000),
      next: { revalidate: 3600 },
    });
    if (res.ok) {
      const data = await res.json();
      awardRoutes = (data.awards ?? []).map((a: any) => ({
        url: `${BASE_URL}/awards/${a.id}`,
        changeFrequency: "daily" as const,
        priority: 0.4,
      }));
    }
  } catch {}

  let galleryRoutes: MetadataRoute.Sitemap = [];
  try {
    const rows = await getGallerySitemapEntries();
    galleryRoutes = rows
      .slice(0, MAX_GALLERY_SITEMAP_ENTRIES)
      .map((row) => ({
        url: `${BASE_URL}/gallery/${row.id}`,
        lastModified: row.updatedAt,
        changeFrequency: "weekly" as const,
        priority: 0.6,
      }));
  } catch {}

  return [...staticRoutes, ...playerRoutes, ...awardRoutes, ...galleryRoutes];
}
