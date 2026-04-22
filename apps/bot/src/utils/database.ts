import * as mariadb from "mariadb";
import config from "./config.js";

const pool = mariadb.createPool(config.DISCORD_DATABASE_URL + "?connectionLimit=10");

export default pool;

export async function closePool() {
  await pool.end();
}
