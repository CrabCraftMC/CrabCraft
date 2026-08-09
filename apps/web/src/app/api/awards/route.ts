import { NextResponse } from "next/server";

interface AwardsResponse {
  awards?: Array<{
    id: string;
    title: string;
    description: string;
    icon: string;
  }>;
}

export async function GET() {
  try {
    const response = await fetch("https://api.crabcraft.net/awards", {
      next: { revalidate: 300 },
    });
    if (!response.ok) throw new Error(`Awards API returned ${response.status}`);
    const data = (await response.json()) as AwardsResponse;

    return NextResponse.json(
      (data.awards ?? []).map((award) => ({
        id: award.id,
        title: award.title,
        description: award.description,
        icon: award.icon,
      })),
      {
        headers: {
          "Cache-Control": "public, s-maxage=300, stale-while-revalidate=3600",
        },
      },
    );
  } catch {
    return NextResponse.json([], { status: 500 });
  }
}
