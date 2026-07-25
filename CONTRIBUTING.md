# Contributing

Thanks for helping improve CrabCraft.

Before opening a pull request:

1. keep the change focused and explain the user-visible effect;
2. add or update regression coverage where practical;
3. run the checks for every affected application; and
4. confirm that no credentials, private player data, build outputs, or local configuration files are included.

Use Bun for TypeScript work and the checked-in Gradle wrapper for Minecraft work. When a PostgreSQL table, column, index, constraint, or enum changes, keep `packages/db/src/schema.ts` and the Java/JDBC schema definitions under `apps/minecraft/velocity` synchronised.

For security-sensitive changes, describe the trust boundary and the test that demonstrates both rejection of the unsafe case and preservation of the legitimate case. Report undisclosed vulnerabilities privately as described in [SECURITY.md](SECURITY.md), not through a pull request.

By submitting a contribution, you confirm that you have the right to contribute it under the repository's GPL-3.0-only licence and any additional licence that applies to the affected third-party component.
