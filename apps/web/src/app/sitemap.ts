import type { MetadataRoute } from "next";
import { gunzipSync } from "zlib";
import { searchUsers } from "@/lib/queries";

export const dynamic = "force-dynamic";
export const revalidate = 3600;

const BASE_URL = "https://crabcraft.net";

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const staticRoutes: MetadataRoute.Sitemap = [
    { url: BASE_URL, changeFrequency: "daily", priority: 1 },
    { url: `${BASE_URL}/leaderboard`, changeFrequency: "hourly", priority: 0.9 },
    { url: `${BASE_URL}/awards`, changeFrequency: "daily", priority: 0.8 },
    { url: `${BASE_URL}/tools/rgb-nickname`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/block-gradient`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${BASE_URL}/tools/circle-generator`, changeFrequency: "monthly", priority: 0.6 },
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
    const res = await fetch("https://map.crabcraft.net/stats/data/summary.json.gz", {
      headers: { "Accept-Encoding": "identity", Referer: "https://crabcraft.net" },
    });
    if (res.ok) {
      const buffer = Buffer.from(await res.arrayBuffer());
      let text: string;
      if (buffer[0] === 0x1f && buffer[1] === 0x8b) {
        text = gunzipSync(buffer).toString();
      } else {
        text = buffer.toString();
      }
      const summary = JSON.parse(text);
      if (summary.awards) {
        awardRoutes = Object.keys(summary.awards).map((key) => ({
          url: `${BASE_URL}/awards/${key}`,
          changeFrequency: "daily" as const,
          priority: 0.4,
        }));
      }
    }
  } catch {}

  return [...staticRoutes, ...playerRoutes, ...awardRoutes];
}
