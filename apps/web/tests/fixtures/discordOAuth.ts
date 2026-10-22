import assert from "node:assert/strict";
import { mock } from "bun:test";
import { NextRequest } from "next/server";

mock.module("@crabcraft/db/queries/auth", () => ({
  getUserForAuth: async () => null,
  updateOnLogin: async () => {},
}));
mock.module("@crabcraft/shared/mojang", () => ({ fetchPlayerName: async () => null }));

const origin = "https://app.example.com";
const issuer = "https://discord.com";
const pkceCookie = "__Secure-authjs.pkce.code_verifier";
const profile = {
  id: "123456789012345678",
  username: "test-user",
  global_name: "Test User",
  avatar: null,
  discriminator: "0",
};
const errors: string[] = [];
console.error = (...values: unknown[]) => { errors.push(values.map(String).join(" ")); };

let challenge: string;
const requests: string[] = [];
// Only replace Discord's HTTP responses; Auth.js and oauth4webapi run unmodified.
globalThis.fetch = (async (input, init) => {
  const url = String(input);
  requests.push(url);
  if (url === "https://discord.com/api/oauth2/token") {
    const body = init?.body as URLSearchParams;
    assert.equal(body.get("code"), "test-code");
    assert.equal(body.get("redirect_uri"), `${origin}/api/auth/callback/discord`);
    const verifier = body.get("code_verifier");
    assert.ok(verifier, "the token exchange must include the PKCE verifier");
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
    assert.equal(Buffer.from(digest).toString("base64url"), challenge);
    return Response.json({ access_token: "test-access-token", token_type: "Bearer", expires_in: 3600 });
  }
  if (url === "https://discord.com/api/users/@me") {
    assert.equal(new Headers(init?.headers).get("authorization"), "Bearer test-access-token");
    return Response.json(profile);
  }
  throw new Error(`Unexpected network request: ${url}`);
}) as typeof fetch;

const { handlers } = await import("../../src/lib/auth");

async function beginLogin() {
  requests.length = 0;
  errors.length = 0;
  const cookies = new Map<string, string>();
  async function request(path: string, body?: URLSearchParams) {
    const request = new NextRequest(`${origin}${path}`, {
      method: body ? "POST" : "GET",
      headers: {
        cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join("; "),
        ...(body ? { "content-type": "application/x-www-form-urlencoded" } : {}),
      },
      body,
    });
    const response = await (body ? handlers.POST : handlers.GET)(request);
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(";")[0];
      const separator = pair.indexOf("=");
      cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
    return response;
  }

  const csrf = await request("/api/auth/csrf");
  const { csrfToken } = await csrf.json() as { csrfToken: string };
  const signin = await request("/api/auth/signin/discord", new URLSearchParams({
    csrfToken,
    callbackUrl: `${origin}/`,
  }));
  const authorisation = new URL(signin.headers.get("location")!);
  assert.equal(`${authorisation.origin}${authorisation.pathname}`, `${issuer}/api/oauth2/authorize`);
  assert.equal(authorisation.searchParams.get("scope"), "identify");
  assert.equal(authorisation.searchParams.get("code_challenge_method"), "S256");
  challenge = authorisation.searchParams.get("code_challenge")!;
  assert.ok(challenge);
  assert.ok(cookies.get(pkceCookie), "sign-in must set the encrypted PKCE cookie");

  async function callback(responseIssuer: string) {
    const params = new URLSearchParams({ code: "test-code", iss: responseIssuer });
    const state = authorisation.searchParams.get("state");
    if (state) params.set("state", state);
    return request(`/api/auth/callback/discord?${params}`);
  }
  return { cookies, request, callback };
}

const valid = await beginLogin();
const completed = await valid.callback(issuer);
assert.equal(completed.headers.get("location"), `${origin}/`, errors.join("\n"));
assert.deepEqual(requests, [
  "https://discord.com/api/oauth2/token",
  "https://discord.com/api/users/@me",
]);
const session = await (await valid.request("/api/auth/session")).json();
assert.equal(session.user.name, profile.global_name);
assert.equal(session.user.discordId, profile.id);
assert.equal(session.user.role, "unverified");
assert.deepEqual(errors, []);

for (const rejection of ["issuer", "missing PKCE", "forged PKCE"] as const) {
  const login = await beginLogin();
  if (rejection === "missing PKCE") login.cookies.delete(pkceCookie);
  if (rejection === "forged PKCE") login.cookies.set(pkceCookie, "forged-cookie");
  const rejected = await login.callback(rejection === "issuer" ? "https://untrusted.example.com" : issuer);
  assert.match(rejected.headers.get("location")!, /\/api\/auth\/error\?error=Configuration$/);
  assert.deepEqual(requests, [], `${rejection} must fail before exchanging the code`);
  assert.match(errors.join("\n"), rejection === "issuer"
    ? /unexpected "iss" \(issuer\) response parameter value/
    : /pkceCodeVerifier value could not be parsed/);
  assert.equal(await (await login.request("/api/auth/session")).json(), null);
}
