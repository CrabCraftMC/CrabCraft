import { describe, expect, test } from "bun:test";
import {
  getGoogleAdMode,
  parseAdSenseConfig,
  type TcfConsentData,
} from "../src/lib/adsense";

function configuredEnvironment(): Record<string, string> {
  return {
    ADSENSE_ENABLED: "true",
    ADSENSE_CLIENT_ID: "ca-pub-1234567890123456",
    ADSENSE_SLOT_ID: "1000000001",
  };
}

function consent(overrides: Partial<TcfConsentData> = {}): TcfConsentData {
  return {
    cmpStatus: "loaded",
    eventStatus: "useractioncomplete",
    gdprApplies: true,
    vendor: { consents: { 755: true } },
    purpose: { consents: { 1: true, 3: true, 4: true } },
    ...overrides,
  };
}

describe("AdSense configuration", () => {
  test("requires explicit enablement, a publisher ID and one shared ad-unit ID", () => {
    const env = configuredEnvironment();
    expect(parseAdSenseConfig(env)).toEqual({
      enabled: true,
      clientId: env.ADSENSE_CLIENT_ID,
      slotId: env.ADSENSE_SLOT_ID,
    });

    for (const value of [undefined, "", "false", "1", "TRUE"]) {
      expect(parseAdSenseConfig({ ...env, ADSENSE_ENABLED: value }).enabled).toBe(false);
    }
    for (const key of ["ADSENSE_CLIENT_ID", "ADSENSE_SLOT_ID"]) {
      expect(parseAdSenseConfig({ ...env, [key]: undefined }).enabled).toBe(false);
      expect(parseAdSenseConfig({ ...env, [key]: "not-a-slot" }).enabled).toBe(false);
    }
  });

  test("rejects malformed publisher and slot identifiers", () => {
    const env = configuredEnvironment();
    for (const clientId of ["", "pub-1234567890123456", "ca-pub-123", "ca-pub-12345678901234567", 'ca-pub-1234567890123456\" onload=\"alert(1)']) {
      const config = parseAdSenseConfig({ ...env, ADSENSE_CLIENT_ID: clientId });
      expect(config.enabled).toBe(false);
      expect(config.clientId).toBeNull();
    }
    for (const slot of ["", "1e10", "-123", "123abc", "1".repeat(21)]) {
      const config = parseAdSenseConfig({ ...env, ADSENSE_SLOT_ID: slot });
      expect(config.enabled).toBe(false);
      expect(config.slotId).toBeNull();
    }
  });

  test("accepts surrounding whitespace in public identifiers", () => {
    const env = configuredEnvironment();
    const config = parseAdSenseConfig({
      ...env,
      ADSENSE_CLIENT_ID: ` ${env.ADSENSE_CLIENT_ID} `,
      ADSENSE_SLOT_ID: " 1000000001 ",
    });
    expect(config.enabled).toBe(true);
    expect(config.clientId).toBe(env.ADSENSE_CLIENT_ID);
    expect(config.slotId).toBe("1000000001");
    expect(parseAdSenseConfig({}).enabled).toBe(false);
  });
});

describe("Google advertising consent", () => {
  test("withholds adverts until the CMP has resolved a valid choice", () => {
    for (const data of [
      null,
      {},
      consent({ cmpStatus: "loading" }),
      consent({ cmpStatus: "error" }),
      consent({ eventStatus: "cmpuishown" }),
      consent({ eventStatus: undefined }),
      consent({ gdprApplies: undefined }),
    ]) {
      expect(getGoogleAdMode(data, 1)).toBeNull();
    }
    expect(getGoogleAdMode(consent({ eventStatus: "tcloaded" }), undefined)).toBe("personalised");
  });

  test("requires explicit storage and Google vendor consent under GDPR", () => {
    for (const overrides of [
      { vendor: undefined },
      { vendor: { consents: {} } },
      { vendor: { consents: { 755: false } } },
      { purpose: undefined },
      { purpose: { consents: { 3: true, 4: true } } },
      { purpose: { consents: { 1: false, 3: true, 4: true } } },
    ]) {
      expect(getGoogleAdMode(consent(overrides), undefined)).toBeNull();
    }
  });

  test("personalises only when both personalisation purposes are consented", () => {
    expect(getGoogleAdMode(consent(), undefined)).toBe("personalised");
    for (const purposes of [
      { 1: true },
      { 1: true, 3: false, 4: true },
      { 1: true, 3: true, 4: false },
      { 1: true, 3: false, 4: false },
    ]) {
      expect(getGoogleAdMode(consent({ purpose: { consents: purposes } }), undefined))
        .toBe("non-personalised");
    }
  });

  test("requires a resolved US opt-out status outside GDPR", () => {
    const data = consent({ gdprApplies: false, vendor: undefined, purpose: undefined });
    for (const status of [undefined, 0, 3, 99]) {
      expect(getGoogleAdMode(data, status)).toBeNull();
    }
    expect(getGoogleAdMode(data, 1)).toBe("personalised");
    expect(getGoogleAdMode(data, 2)).toBe("personalised");
  });

  test("always honours a known US opt-out, even alongside an affirmative TCF record", () => {
    expect(getGoogleAdMode(consent(), 3)).toBeNull();
  });
});
