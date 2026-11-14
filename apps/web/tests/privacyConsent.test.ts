import { describe, expect, test } from "bun:test";
import { effectivePrivacyPreferences, hasRevokedPermission, parsePrivacyConsent, PRIVACY_CONSENT_LIFETIME, storePrivacyConsent } from "../src/lib/privacyConsent";

const now = 1_800_000_000_000;
const stored = { version: 1 as const, savedAt: now - 1, analytics: true, advertising: false };

describe("privacy choices", () => {
  test("defaults to analytics on and advertising off before an advertising choice", () => {
    expect(effectivePrivacyPreferences(null, now)).toEqual({ analytics: true, advertising: false });
    expect(parsePrivacyConsent(JSON.stringify(stored), now)).toEqual(stored);
  });
  test("rejects malformed, future and obsolete choices", () => {
    for (const value of [null, "", "{", "null", "true", JSON.stringify({ ...stored, version: 2 }),
      JSON.stringify({ ...stored, savedAt: now + 1 }),
      JSON.stringify({ ...stored, analytics: "true" }), JSON.stringify({ ...stored, advertising: undefined })]) {
      expect(parsePrivacyConsent(value, now)).toBeNull();
    }
    expect(parsePrivacyConsent(JSON.stringify({ ...stored, savedAt: now - PRIVACY_CONSENT_LIFETIME + 1 }), now)).not.toBeNull();
  });
  test("retains advertising refusals and analytics preferences beyond twelve months", () => {
    for (const analytics of [true, false]) {
      const consent = parsePrivacyConsent(JSON.stringify({ ...stored, analytics, savedAt: now - PRIVACY_CONSENT_LIFETIME * 3 }), now);
      expect(effectivePrivacyPreferences(consent, now)).toEqual({ analytics, advertising: false });
    }
  });
  test("expires advertising consent without losing an existing analytics opt-out", () => {
    const consent = parsePrivacyConsent(JSON.stringify({ ...stored, analytics: false, advertising: true, savedAt: now - PRIVACY_CONSENT_LIFETIME }), now);
    expect(consent).not.toBeNull();
    expect(effectivePrivacyPreferences(consent, now - 1)).toEqual({ analytics: false, advertising: true });
    expect(effectivePrivacyPreferences(consent, now)).toEqual({ analytics: false, advertising: false });
  });
  test("removes stale acceptance if a withdrawal cannot be saved", () => {
    let value: string | null = JSON.stringify({ ...stored, advertising: true });
    const storage = {
      setItem: () => { throw new Error("Storage quota exceeded"); },
      getItem: () => value,
      removeItem: () => { value = null; },
    };
    expect(storePrivacyConsent(storage, { analytics: false, advertising: false }, now)).toBe("cleared");
    expect(value).toBeNull();
    expect(storePrivacyConsent({ ...storage, removeItem: () => { throw new Error("Blocked"); } }, stored, now)).toBe("unavailable");
  });
  test("preserves each saved permission independently", () => {
    expect(effectivePrivacyPreferences({ ...stored, analytics: true, advertising: true }, now)).toEqual({ analytics: true, advertising: true });
    expect(effectivePrivacyPreferences(stored, now)).toEqual({ analytics: true, advertising: false });
    expect(effectivePrivacyPreferences({ ...stored, analytics: false, advertising: true }, now)).toEqual({ analytics: false, advertising: true });
    expect(effectivePrivacyPreferences({ ...stored, analytics: false, advertising: false }, now)).toEqual({ analytics: false, advertising: false });
  });
  test("recognises withdrawal of either independent permission", () => {
    expect(hasRevokedPermission({ analytics: true, advertising: false }, { analytics: false, advertising: false })).toBe(true);
    expect(hasRevokedPermission({ analytics: false, advertising: true }, { analytics: true, advertising: false })).toBe(true);
    expect(hasRevokedPermission({ analytics: false, advertising: false }, { analytics: true, advertising: true })).toBe(false);
  });
});
