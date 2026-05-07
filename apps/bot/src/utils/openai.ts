import config from "./config.js";
import logger from "./logger.js";

const OPENAI_URL = "https://api.openai.com/v1/chat/completions";

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
    res = await fetch(OPENAI_URL, {
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
                image_url: { url: imageUrl, detail: "low" },
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
