import { expect, test } from "bun:test";
import { runInNewContext } from "node:vm";
import {
  BLOCK_GRADIENT_LAYOUT_KEY,
  blockGradientLayoutScript,
  getBlockGradientLoadingHeight,
} from "../src/lib/blockGradientLayout";

const cached = { width: 390, viewportHeight: 844, route: "/tools/block-gradient", settings: '{"steps":22}', height: 2048.5 };

test("reuses only a height measured for the same viewport, route and saved settings", () => {
  expect(getBlockGradientLoadingHeight(cached, 390, 844, cached.route, cached.settings)).toBe(2048.5);
  expect(getBlockGradientLoadingHeight(cached, 391, 844, cached.route, cached.settings)).toBeNull();
  expect(getBlockGradientLoadingHeight(cached, 390, 800, cached.route, cached.settings)).toBeNull();
  expect(getBlockGradientLoadingHeight(cached, 390, 844, `${cached.route}?share=another`, cached.settings)).toBeNull();
  expect(getBlockGradientLoadingHeight(cached, 390, 844, cached.route, '{"steps":12}')).toBeNull();
  for (const value of [null, [], {}, { ...cached, viewportHeight: undefined }, { ...cached, height: NaN }, { ...cached, height: Infinity }, { ...cached, height: 0 }, { ...cached, height: -10 }]) {
    expect(getBlockGradientLoadingHeight(value, 390, 844, cached.route, cached.settings)).toBeNull();
  }
});

function runScript(value: string | null, settings = cached.settings, search = "", viewportHeight = cached.viewportHeight) {
  const properties = new Map([["--block-gradient-loading-height", "stale"]]);
  runInNewContext(blockGradientLayoutScript, {
    innerWidth: 390,
    innerHeight: viewportHeight,
    location: { pathname: cached.route, search },
    sessionStorage: { getItem: (key: string) => key === BLOCK_GRADIENT_LAYOUT_KEY ? value : null },
    localStorage: { getItem: () => settings },
    document: { documentElement: { style: {
      removeProperty: (key: string) => properties.delete(key),
      setProperty: (key: string, value: string) => properties.set(key, value),
    } } },
  });
  return properties.get("--block-gradient-loading-height");
}

test("pre-paint script reserves the matching height and rejects stale or malformed caches", () => {
  expect(runScript(JSON.stringify(cached))).toBe("2048.5px");
  expect(runScript(JSON.stringify(cached), cached.settings, "", 800)).toBeUndefined();
  expect(runScript(JSON.stringify(cached), '{"steps":12}')).toBeUndefined();
  expect(runScript(JSON.stringify(cached), cached.settings, "?share=another")).toBeUndefined();
  expect(runScript("invalid JSON")).toBeUndefined();
  expect(runScript(null)).toBeUndefined();
});

test("pre-paint script tolerates unavailable browser storage", () => {
  expect(() => runInNewContext(blockGradientLayoutScript, {
    document: { documentElement: { style: { removeProperty() {} } } },
    get sessionStorage() { throw new Error("Storage unavailable"); },
  })).not.toThrow();
});
