export const PRIVACY_CONSENT_KEY = "crabcraft-privacy-v1";
export const PRIVACY_CONSENT_LIFETIME = 180 * 24 * 60 * 60 * 1000;

export type PrivacyPreferences = { analytics: boolean; advertising: boolean };
export type PrivacyConsent = PrivacyPreferences & { version: 1; savedAt: number };
export const ESSENTIAL_ONLY: PrivacyPreferences = { analytics: false, advertising: false };

export function parsePrivacyConsent(value: string | null, now = Date.now()): PrivacyConsent | null {
  if (!value) return null;
  try {
    const consent = JSON.parse(value);
    if (consent?.version !== 1 || typeof consent.analytics !== "boolean" ||
      typeof consent.advertising !== "boolean" || typeof consent.savedAt !== "number" ||
      !Number.isFinite(consent.savedAt) || consent.savedAt > now) return null;
    return {
      version: 1, savedAt: consent.savedAt,
      analytics: consent.analytics, advertising: consent.advertising,
    };
  } catch {
    return null;
  }
}

export function storePrivacyConsent(
  storage: Pick<Storage, "setItem" | "getItem" | "removeItem">,
  preferences: PrivacyPreferences,
  now = Date.now(),
): "saved" | "cleared" | "unavailable" {
  try {
    storage.setItem(PRIVACY_CONSENT_KEY, JSON.stringify({ version: 1, savedAt: now, ...preferences }));
    return "saved";
  } catch {
    // Never leave an old acceptance in place after a failed withdrawal write.
    try {
      storage.removeItem(PRIVACY_CONSENT_KEY);
      if (storage.getItem(PRIVACY_CONSENT_KEY) === null) return "cleared";
    } catch { /* The browser may block both writes and removals. */ }
    return "unavailable";
  }
}

export function effectivePrivacyPreferences(
  consent: PrivacyConsent | null,
  now = Date.now(),
): PrivacyPreferences {
  return !consent ? { analytics: true, advertising: false } : {
    analytics: consent.analytics,
    advertising: consent.advertising && now - consent.savedAt < PRIVACY_CONSENT_LIFETIME,
  };
}

export function hasRevokedPermission(previous: PrivacyPreferences, next: PrivacyPreferences): boolean {
  return (previous.analytics && !next.analytics) || (previous.advertising && !next.advertising);
}
