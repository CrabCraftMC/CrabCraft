import { NextResponse } from "next/server";

export async function GET() {
  try {
    const res = await fetch("https://api.crabcraft.net/players", {
      signal: AbortSignal.timeout(5000),
      cache: "no-store",
    });

    if (!res.ok) {
      return NextResponse.json({ count: 0, players: [] }, { status: 502 });
    }

    const data = await res.json();
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ count: 0, players: [] }, { status: 502 });
  }
}
