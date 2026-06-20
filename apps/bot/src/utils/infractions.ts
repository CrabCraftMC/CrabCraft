import logger from "./logger.js";
import config from "./config.js";

const INFRACTION_TYPES = new Set(["ban", "mute", "warning", "kick"]);

export interface PublicInfraction {
  type: "ban" | "mute" | "warning" | "kick";
  id: number;
  reason: string | null;
  staff: string | null;
  created_at: number;
  expires_at: number | null;
  active: boolean | null;
  removed: boolean;
  removed_by: string | null;
  removed_at: number | null;
}

export interface TicketInfractionInfo {
  username: string;
  uuid: string | null;
  infractions: PublicInfraction[] | null;
  error?: string;
}

export async function fetchPlayerInfractions(
  username: string,
  uuid: string,
  limit = 10,
): Promise<TicketInfractionInfo> {
  const safeLimit = Math.max(1, Math.min(25, limit));
  const url = `${config.CRABCRAFT_API_URL}/players/${uuid}/infractions?limit=${safeLimit}`;
  try {
    const ctrl = new AbortController();
    const timeout = setTimeout(() => ctrl.abort(), 5000);
    const res = await fetch(url, { signal: ctrl.signal }).finally(() =>
      clearTimeout(timeout),
    );
    if (!res.ok) {
      return {
        username,
        uuid,
        infractions: null,
        error: `Infraction lookup failed (${res.status}).`,
      };
    }
    const body = await res.json();
    const infractions = normalizeInfractionsResponse(body);
    const responseUuid = isRecord(body) && typeof body.uuid === "string"
      ? body.uuid
      : uuid;
    return {
      username,
      uuid: responseUuid,
      infractions,
    };
  } catch (error) {
    logger.warn(
      `ticket infractions: fetch failed for ${uuid}: ${(error as Error).message}`,
    );
    return {
      username,
      uuid,
      infractions: null,
      error: "Infraction lookup timed out or the API was unreachable.",
    };
  }
}

function normalizeInfractionsResponse(body: unknown): PublicInfraction[] {
  if (!isRecord(body) || !Array.isArray(body.infractions)) return [];

  const infractions: PublicInfraction[] = [];
  let dropped = 0;
  for (const row of body.infractions) {
    const infraction = normalizeInfraction(row);
    if (infraction) {
      infractions.push(infraction);
    } else {
      dropped += 1;
    }
  }
  if (dropped > 0) {
    logger.warn(`ticket infractions: dropped ${dropped} malformed rows`);
  }
  return infractions;
}

function normalizeInfraction(row: unknown): PublicInfraction | null {
  if (!isRecord(row)) return null;
  if (typeof row.type !== "string" || !INFRACTION_TYPES.has(row.type)) {
    return null;
  }

  const id = asNumber(row.id);
  const createdAt = asNumber(row.created_at);
  const removed = asBoolean(row.removed);
  if (id === null || createdAt === null || removed === null) {
    return null;
  }

  return {
    type: row.type as PublicInfraction["type"],
    id,
    reason: asNullableString(row.reason),
    staff: asNullableString(row.staff),
    created_at: createdAt,
    expires_at: asNullableNumber(row.expires_at),
    active: asNullableBoolean(row.active),
    removed,
    removed_by: asNullableString(row.removed_by),
    removed_at: asNullableNumber(row.removed_at),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function asNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function asNullableNumber(value: unknown): number | null {
  return value === null || value === undefined ? null : asNumber(value);
}

function asBoolean(value: unknown): boolean | null {
  return typeof value === "boolean" ? value : null;
}

function asNullableBoolean(value: unknown): boolean | null {
  return value === null || value === undefined ? null : asBoolean(value);
}

function asNullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim().length > 0
    ? value
    : null;
}
