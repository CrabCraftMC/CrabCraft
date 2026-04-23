import { NextRequest, NextResponse } from "next/server";
import { searchUsers } from "@/lib/queries";

const WINDOW_MS = 60_000;
const MAX_REQUESTS = 30;

const hits = new Map<string, { count: number; resetAt: number }>();

function isRateLimited(ip: string): boolean {
  const now = Date.now();
  const entry = hits.get(ip);
  if (!entry || now >= entry.resetAt) {
    hits.set(ip, { count: 1, resetAt: now + WINDOW_MS });
    return false;
  }
  entry.count++;
  return entry.count > MAX_REQUESTS;
}

// Prune stale entries every 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [ip, entry] of hits) {
    if (now >= entry.resetAt) hits.delete(ip);
  }
}, 300_000).unref();

export async function GET(request: NextRequest) {
  const ip = request.headers.get("cf-connecting-ip")
    ?? request.headers.get("x-forwarded-for")?.split(",")[0]?.trim()
    ?? "unknown";

  if (isRateLimited(ip)) {
    return NextResponse.json({ error: "Too many requests" }, { status: 429 });
  }

  const q = request.nextUrl.searchParams.get("q")?.trim() || "";

  if (q.length < 2 || q.length > 32) {
    return NextResponse.json([]);
  }

  try {
    const limit = Math.min(Number(request.nextUrl.searchParams.get("limit")) || 10, 25);
    const results = await searchUsers(q, limit);
    return NextResponse.json(results);
  } catch {
    return NextResponse.json([], { status: 500 });
  }
}
