import { NextRequest, NextResponse } from "next/server";
import { searchUsers } from "@/lib/queries";

export async function GET(request: NextRequest) {
  const q = request.nextUrl.searchParams.get("q")?.trim() || "";

  if (q.length < 2 || q.length > 32) {
    return NextResponse.json([]);
  }

  try {
    const results = await searchUsers(q);
    return NextResponse.json(results);
  } catch {
    return NextResponse.json([], { status: 500 });
  }
}
