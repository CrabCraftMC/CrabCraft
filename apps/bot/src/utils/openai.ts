import config from "./config.js";
import logger from "./logger.js";

const OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
const OPENAI_TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions";
const REQUEST_TIMEOUT_MS = 20_000;
const AUDIO_DOWNLOAD_TIMEOUT_MS = 10_000;
const MAX_AUDIO_BYTES = 2 * 1024 * 1024;

interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string } }>;
  error?: { message?: string };
}

async function fetchWithTimeout(
  input: string,
  init: RequestInit,
  timeoutMs: number,
): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

async function readBodyLimited(
  response: Response,
  maxBytes: number,
): Promise<Uint8Array | null> {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > maxBytes) return null;
  if (!response.body) return null;

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

/**
 * Sends an image URL to gpt-4o-mini and asks for the integer shown.
 * Returns null if no key is configured, the API errors, no integer
 * is detected, or the model replies "none".
 */
export async function extractNumberFromImage(
  imageUrl: string,
): Promise<number | null> {
  if (!config.OPENAI_API_KEY) return null;

  let res: Response;
  try {
    res = await fetchWithTimeout(OPENAI_CHAT_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${config.OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: "gpt-4o-mini",
        max_tokens: 16,
        messages: [
          {
            role: "system",
            content:
              "You read images and report any integer shown. Reply with JUST the integer (no commas, no words), or the single word 'none' if no clear integer is present.",
          },
          {
            role: "user",
            content: [
              {
                type: "image_url",
                image_url: { url: imageUrl, detail: "high" },
              },
            ],
          },
        ],
      }),
    }, REQUEST_TIMEOUT_MS);
  } catch (error) {
    logger.error("OpenAI request failed:", error);
    return null;
  }

  if (!res.ok) {
    logger.error(`OpenAI returned ${res.status}: ${await res.text().catch(() => "")}`);
    return null;
  }

  const data = (await res.json().catch(() => null)) as ChatCompletionResponse | null;
  const reply = data?.choices?.[0]?.message?.content?.trim() ?? "";
  logger.info(`[openai] vision reply: ${JSON.stringify(reply)}`);
  if (!reply || reply.toLowerCase() === "none") return null;

  const match = reply.match(/-?\d+/);
  if (!match) return null;
  const n = parseInt(match[0], 10);
  return Number.isFinite(n) ? n : null;
}

/**
 * Downloads an audio file and sends it to Whisper. Returns the trimmed
 * transcript, or null if no key, download/API failure, or empty reply.
 * Caller is responsible for enforcing any duration cap before invoking.
 */
export async function transcribeAudio(
  audioUrl: string,
  promptHint?: string,
): Promise<string | null> {
  if (!config.OPENAI_API_KEY) return null;

  let audioRes: Response;
  try {
    audioRes = await fetchWithTimeout(audioUrl, {}, AUDIO_DOWNLOAD_TIMEOUT_MS);
  } catch (error) {
    logger.error("Audio download failed:", error);
    return null;
  }
  if (!audioRes.ok) {
    logger.error(`Audio download returned ${audioRes.status}`);
    return null;
  }
  const audioBytes = await readBodyLimited(audioRes, MAX_AUDIO_BYTES);
  if (!audioBytes) {
    logger.error("Audio download exceeded the 2 MiB limit or had no body");
    return null;
  }
  const audioBuffer = new ArrayBuffer(audioBytes.byteLength);
  new Uint8Array(audioBuffer).set(audioBytes);
  const audioBlob = new Blob([audioBuffer], {
    type: audioRes.headers.get("content-type") ?? "audio/ogg",
  });

  const form = new FormData();
  form.append("file", audioBlob, "voice.ogg");
  form.append("model", "whisper-1");
  form.append("response_format", "text");
  if (promptHint) form.append("prompt", promptHint);

  let res: Response;
  try {
    res = await fetchWithTimeout(OPENAI_TRANSCRIBE_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${config.OPENAI_API_KEY}` },
      body: form,
    }, REQUEST_TIMEOUT_MS);
  } catch (error) {
    logger.error("Whisper request failed:", error);
    return null;
  }
  if (!res.ok) {
    logger.error(
      `Whisper returned ${res.status}: ${await res.text().catch(() => "")}`,
    );
    return null;
  }

  const transcript = (await res.text().catch(() => "")).trim();
  logger.info(`[openai] whisper transcript: ${JSON.stringify(transcript)}`);
  return transcript || null;
}
