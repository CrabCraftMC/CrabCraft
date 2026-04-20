import { NextRequest, NextResponse } from "next/server";

const BASE = "https://map.crabcraft.net/stats/data";
const HEADERS = { Referer: "https://crabcraft.net" };

function getCachePrefix(uuid: string): string {
  return uuid.replace(/-/g, "").slice(0, 2);
}

export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ key: string }> }
) {
  const { key } = await params;

  // Validate key format (alphanumeric + underscores only)
  if (!/^[a-z0-9_]+$/.test(key)) {
    return NextResponse.json([], { status: 400 });
  }

  try {
    // Fetch rankings
    const rankingsRes = await fetch(`${BASE}/rankings/${key}.json`, {
      headers: HEADERS,
      next: { revalidate: 60 },
    });
    if (!rankingsRes.ok) return NextResponse.json([], { status: 404 });

    const rankings: { uuid: string; value: number }[] =
      await rankingsRes.json();

    // Deduplicate cache prefixes and fetch player names
    const prefixes = [...new Set(rankings.map((r) => getCachePrefix(r.uuid)))];
    const cacheResults = await Promise.all(
      prefixes.map((prefix) =>
        fetch(`${BASE}/playercache/${prefix}.json`, {
          headers: HEADERS,
          next: { revalidate: 3600 },
        })
          .then((r) => (r.ok ? r.json() : []))
          .catch(() => [])
      )
    );

    // Build UUID → name lookup
    const nameMap = new Map<string, string>();
    for (const entries of cacheResults) {
      for (const entry of entries as { uuid: string; name: string }[]) {
        nameMap.set(entry.uuid, entry.name);
      }
    }

    // Merge rankings with names
    const result = rankings.map((r, i) => ({
      rank: i + 1,
      uuid: r.uuid,
      name: nameMap.get(r.uuid) ?? "Unknown",
      value: r.value,
    }));

    return NextResponse.json(result);
  } catch {
    return NextResponse.json([], { status: 500 });
  }
}
