import config from "./config.js";
import logger from "./logger.js";

const OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
const OPENAI_TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions";

interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string } }>;
  error?: { message?: string };
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
    res = await fetch(OPENAI_CHAT_URL, {
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
    });
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
  logger.info(`[openai] vision reply for ${imageUrl}: ${JSON.stringify(reply)}`);
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
    audioRes = await fetch(audioUrl);
  } catch (error) {
    logger.error("Audio download failed:", error);
    return null;
  }
  if (!audioRes.ok) {
    logger.error(`Audio download returned ${audioRes.status}`);
    return null;
  }
  const audioBlob = await audioRes.blob();

  const form = new FormData();
  form.append("file", audioBlob, "voice.ogg");
  form.append("model", "whisper-1");
  form.append("response_format", "text");
  if (promptHint) form.append("prompt", promptHint);

  let res: Response;
  try {
    res = await fetch(OPENAI_TRANSCRIBE_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${config.OPENAI_API_KEY}` },
      body: form,
    });
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
  logger.info(`[openai] whisper transcript for ${audioUrl}: ${JSON.stringify(transcript)}`);
  return transcript || null;
}
