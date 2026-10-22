import { expect, test } from "bun:test";

// Other tests mock the auth module globally, so exercise the real handlers in isolation.
test("Discord login validates the issuer and PKCE before creating a session", async () => {
  const env = { ...process.env };
  for (const name of Object.keys(env)) {
    if (name.startsWith("AUTH_") || name.startsWith("NEXTAUTH_")) delete env[name];
  }
  const child = Bun.spawn({
    cmd: [process.execPath, "--no-env-file", new URL("./fixtures/discordOAuth.ts", import.meta.url).pathname],
    env: {
      ...env,
      NODE_ENV: "production",
      AUTH_SECRET: "discord-oauth-regression-secret",
      AUTH_DISCORD_ID: "test-client-id",
      AUTH_DISCORD_SECRET: "test-client-secret",
    },
    stdout: "pipe",
    stderr: "pipe",
  });
  const [exitCode, stdout, stderr] = await Promise.all([
    child.exited,
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
  ]);

  expect({ exitCode, stdout, stderr }).toEqual({ exitCode: 0, stdout: "", stderr: "" });
});
