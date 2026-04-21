import { NextRequest, NextResponse } from "next/server";
import {
  getAwardLeaderboard,
  getCurrentSeason,
  getAwardDefinition,
  AWARD_AGGREGATE_SERVER_ID,
} from "@/lib/queries";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ key: string }> },
) {
  const { key } = await params;

  if (!/^[a-z0-9_]+$/.test(key)) {
    return NextResponse.json([], { status: 400 });
  }

  const meta = await getAwardDefinition(key);
  if (!meta) {
    return NextResponse.json([], { status: 404 });
  }

  const searchParams = request.nextUrl.searchParams;
  const serverId = searchParams.get("server") ?? AWARD_AGGREGATE_SERVER_ID;

  try {
    const currentSeason = await getCurrentSeason();
    if (!currentSeason) return NextResponse.json([], { status: 404 });

    const entries = await getAwardLeaderboard(
      key,
      currentSeason.id,
      serverId,
      100,
    );

    return NextResponse.json(
      entries.map((e) => ({
        rank: e.rank,
        uuid: e.minecraft_uuid,
        name: e.minecraft_username ?? "Unknown",
        value: e.score,
        medal: e.medal,
      })),
    );
  } catch {
    return NextResponse.json([], { status: 500 });
  }
}
