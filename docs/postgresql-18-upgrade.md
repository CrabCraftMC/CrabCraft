# PostgreSQL 18 development-volume upgrade

`docker-compose.dev.yml` is local development infrastructure. PostgreSQL 18
uses a different data-directory layout, so it must not reuse the PostgreSQL 17
`pg_data` volume directly. The Compose file creates a separate `pg18_data`
volume and leaves the old volume available for export or rollback. This follows
the official image's [PostgreSQL 18+ `PGDATA` guidance][pgdata].

If the old database is disposable, start PostgreSQL 18 normally and run the
schema push:

```sh
docker compose -f docker-compose.dev.yml up -d --wait postgres
DATABASE_URL=postgresql://crabcraft:crabcraft_dev@localhost:5432/crabcraft \
  bun run --cwd packages/db db:push
```

To preserve data from PostgreSQL 17, use the following dump-and-restore flow.
Do not run `docker compose down -v` or delete the old volume until the restored
database has been verified.

1. Stop the development database without deleting its volume:

   ```sh
   docker compose -f docker-compose.dev.yml down
   docker volume ls --filter label=com.docker.compose.volume=pg_data
   ```

   Set `OLD_VOLUME` to the PostgreSQL 17 volume belonging to this checkout. Its
   name is normally `<compose-project>_pg_data`.

   ```sh
   OLD_VOLUME=crabcraft_pg_data
   ```

2. Start PostgreSQL 17 against that volume and create a logical backup:

   ```sh
   docker run --name crabcraft-pg17-export --detach \
     --mount source="$OLD_VOLUME",target=/var/lib/postgresql/data \
     postgres:17-alpine
   docker exec crabcraft-pg17-export \
     pg_dump --username crabcraft --dbname crabcraft \
       --format=custom --no-owner --no-privileges \
     > crabcraft-pg17.dump
   test -s crabcraft-pg17.dump
   shasum -a 256 crabcraft-pg17.dump
   docker stop crabcraft-pg17-export
   docker rm crabcraft-pg17-export
   ```

3. Start the new PostgreSQL 18 database and restore the backup:

   ```sh
   docker compose -f docker-compose.dev.yml up -d --wait postgres
   docker compose -f docker-compose.dev.yml exec -T postgres \
     pg_restore --username crabcraft --dbname crabcraft \
       --clean --if-exists --no-owner --no-privileges --exit-on-error \
     < crabcraft-pg17.dump
   ```

4. Verify the server, schema, and application data before removing any backup:

   ```sh
   docker compose -f docker-compose.dev.yml exec -T postgres \
     psql --username crabcraft --dbname crabcraft \
       --command='SHOW server_version;'
   DATABASE_URL=postgresql://crabcraft:crabcraft_dev@localhost:5432/crabcraft \
     bun run --cwd packages/db db:push
   ```

   Check important local records through the bot or web app as well. Keep the
   PostgreSQL 17 volume and `crabcraft-pg17.dump` until those checks pass. To
   roll back, stop PostgreSQL 18 and run PostgreSQL 17 with `OLD_VOLUME`; the
   migration does not modify that volume.

This procedure does not upgrade a deployed database. The deployed services use
an externally supplied `DATABASE_URL`; follow that database provider's backup
and major-version upgrade process separately.

[pgdata]: https://github.com/docker-library/docs/blob/master/postgres/README.md#pgdata
