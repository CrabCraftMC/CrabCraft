import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import * as schema from "./schema";

const connectionString = process.env.DATABASE_URL!;

const client = postgres(connectionString);
export const db = drizzle(client, { schema });
export type Database = typeof db;

let closing: Promise<void> | undefined;

/** Close the PostgreSQL pool for short-lived scripts. */
export function closeDatabase(): Promise<void> {
  closing ??= client.end({ timeout: 5 });
  return closing;
}
