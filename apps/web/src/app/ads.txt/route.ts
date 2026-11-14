import { parseAdSenseConfig } from "@/lib/adsense";

export const dynamic = "force-dynamic";

export function GET() {
  const { clientId } = parseAdSenseConfig(process.env);
  if (!clientId) return new Response("", { status: 404 });

  return new Response(`google.com, ${clientId.slice(3)}, DIRECT, f08c47fec0942fa0\n`, {
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "public, max-age=3600",
    },
  });
}
