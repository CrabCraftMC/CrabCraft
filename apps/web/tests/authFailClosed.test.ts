import { describe, expect, mock, test } from "bun:test";
import { encode } from "next-auth/jwt";
import Discord from "next-auth/providers/discord";

const AUTH_SECRET = "auth-fail-closed-regression-secret";
const SECURE_SESSION_COOKIE = "__Secure-authjs.session-token";

let requestHeaders = new Headers();

mock.module("next/headers", () => ({
  headers: () => requestHeaders,
  cookies: async () => ({ getAll: () => [], set() {} }),
}));

const { default: NextAuth } = await import("next-auth");

function setRequestCookie(cookie?: string) {
  requestHeaders = new Headers({
    host: "app.example.com",
    "x-forwarded-proto": "https",
  });
  if (cookie) requestHeaders.set("cookie", cookie);
}

describe("Auth.js session checks", () => {
  test("configuration errors fail closed instead of returning a truthy error object", async () => {
    setRequestCookie();
    const { auth } = NextAuth({
      secret: AUTH_SECRET,
      trustHost: true,
      logger: { error() {}, warn() {}, debug() {} },
      providers: [
        {
          id: "broken",
          name: "Broken",
          type: "oidc",
          clientId: "client-id",
          clientSecret: "client-secret",
        },
      ],
    });

    expect(await auth()).toBeNull();
  });

  test("valid anonymous and authenticated sessions keep their normal behavior", async () => {
    setRequestCookie();
    const { auth } = NextAuth({
      secret: AUTH_SECRET,
      trustHost: true,
      providers: [
        Discord({ clientId: "client-id", clientSecret: "client-secret" }),
      ],
    });

    expect(await auth()).toBeNull();

    const token = await encode({
      secret: AUTH_SECRET,
      salt: SECURE_SESSION_COOKIE,
      token: {
        sub: "123",
        name: "Test User",
        email: "test@example.com",
        picture: null,
      },
    });
    setRequestCookie(`${SECURE_SESSION_COOKIE}=${token}`);
    const session = await auth();

    expect(session?.user).toMatchObject({
      name: "Test User",
      email: "test@example.com",
      image: null,
    });
  });
});
