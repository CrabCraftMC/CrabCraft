import { gunzipSync } from "zlib";

export async function fetchGzipJson<T = any>(
  url: string,
  options: { revalidate?: number; timeout?: number; headers?: Record<string, string> } = {}
): Promise<T | null> {
  const { revalidate = 60, timeout = 10000, headers = {} } = options;
  try {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(timeout),
      headers: { "Accept-Encoding": "identity", ...headers },
      next: { revalidate },
    });
    const buffer = Buffer.from(await res.arrayBuffer());
    let text: string;
    if (buffer[0] === 0x1f && buffer[1] === 0x8b) {
      text = gunzipSync(buffer).toString();
    } else {
      text = buffer.toString();
    }
    if (text.trimStart().startsWith("<")) return null;
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}
