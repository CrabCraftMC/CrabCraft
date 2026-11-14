export type AdSenseConfig = {
  enabled: boolean;
  clientId: string | null;
  slotId: string | null;
};

export function parseAdSenseConfig(env: Record<string, string | undefined>): AdSenseConfig {
  const client = env.ADSENSE_CLIENT_ID?.trim() ?? "";
  const clientId = /^ca-pub-\d{16}$/.test(client) ? client : null;
  const slot = env.ADSENSE_SLOT_ID?.trim() ?? "";
  const slotId = /^\d{1,20}$/.test(slot) ? slot : null;
  return {
    enabled: env.ADSENSE_ENABLED === "true" && clientId !== null && slotId !== null,
    clientId,
    slotId,
  };
}

export type TcfConsentData = {
  cmpStatus?: string;
  eventStatus?: string;
  gdprApplies?: boolean;
  listenerId?: number;
  purpose?: { consents?: Partial<Record<number, boolean>> };
  vendor?: { consents?: Partial<Record<number, boolean>> };
};

export type GoogleAdMode = "personalised" | "non-personalised";

export function getGoogleAdMode(
  consent: TcfConsentData | null,
  usOptOutStatus: number | undefined,
): GoogleAdMode | null {
  if (!consent || consent.cmpStatus !== "loaded" ||
    !["tcloaded", "useractioncomplete"].includes(consent.eventStatus ?? "") ||
    usOptOutStatus === 3) return null;

  if (consent.gdprApplies === false) {
    // Google's US states API: 1 = does not apply, 2 = not opted out.
    return usOptOutStatus === 1 || usOptOutStatus === 2 ? "personalised" : null;
  }
  if (consent.gdprApplies !== true ||
    consent.vendor?.consents?.[755] !== true ||
    consent.purpose?.consents?.[1] !== true) return null;

  return consent.purpose.consents[3] === true && consent.purpose.consents[4] === true
    ? "personalised"
    : "non-personalised";
}
