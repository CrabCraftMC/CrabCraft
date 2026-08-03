# Gallery production setup

The Gallery is a one-way projection of the configured Discord media channels.
The bot reads each post and its tags, copies image attachments to durable object
storage, and only then writes the public snapshot to PostgreSQL. The web app
reads published snapshots from PostgreSQL; it never depends on expiring Discord
attachment URLs.

## 1. Configure the Discord channels

Add the seven media-channel IDs to `apps/bot/config.json` using the season
mapping below. Keep the other settings unchanged.

```json
{
  "gallery": {
    "channels": [
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "1" },
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "2" },
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "3" },
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "4" },
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "5" },
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "6" },
      { "channelId": "DISCORD_CHANNEL_ID", "seasonId": "7" }
    ]
  }
}
```

The bot must be able to see every configured media channel and its public
threads. Grant it `View Channel` and `Read Message History`. In the Discord
Developer Portal, enable the privileged **Message Content Intent**. Gallery
synchronisation uses the `Guilds`, `Guild Messages`, and `Message Content`
gateway intents. It does not need permission to send or manage messages, the
`Manage Threads` permission, or a Guild Expressions permission or intent.

## 2. Provision durable image storage

The production origin is the existing Backblaze B2 bucket served through
`cdn.crabcraft.net`. Backblaze's S3-compatible endpoint is used only for
authenticated bot operations; website visitors use the public CDN hostname.

1. Make the B2 bucket public, then create a standard application key restricted
   to that bucket and, where the console permits it, the `gallery/` file-name
   prefix. It needs `listAllBucketNames`, `readFiles`, `writeFiles`, and
   `deleteFiles`; do not use the master application key. Record the **keyID** as
   the S3 access-key ID and the **applicationKey** as the S3 secret-access key.
   Backblaze documents these mappings and the compatibility permission in its
   [S3-compatible application-key guide][b2-app-keys].
2. Copy the bucket's S3 endpoint from the Backblaze Buckets page. It must be the
   regional HTTPS endpoint, such as
   `https://s3.eu-central-003.backblazeb2.com`, without the bucket name. Set the
   region to the matching endpoint segment (`eu-central-003` in this example),
   not `auto`; see [Backblaze's endpoint documentation][b2-s3-endpoint].
3. Keep `cdn.crabcraft.net` proxied by Cloudflare and mapped to this bucket's B2
   download origin. The Cloudflare URL rewrite must preserve the incoming path
   and prepend `/file/BUCKET_NAME` at the origin. Consequently, the public URL
   `https://cdn.crabcraft.net/gallery/example.webp` must return the B2 object
   whose exact key is `gallery/example.webp`. Do not include `/file/` or the
   bucket name in `GALLERY_MEDIA_BASE_URL`. Follow Backblaze's
   [Cloudflare CDN guide][b2-cloudflare] when creating or auditing this mapping.
4. Add the following mandatory B2 Lifecycle Rule for the `gallery/` prefix:

   ```json
   {
     "fileNamePrefix": "gallery/",
     "daysFromUploadingToHiding": null,
     "daysFromHidingToDeleting": 1
   }
   ```

   Do not set `daysFromUploadingToHiding`: that would eventually hide live
   Gallery images. The rule permanently removes objects after the bot's S3
   delete hides them and removes hidden previous versions after replacement.
   Without it, B2 retains those versions indefinitely and continues charging
   for their storage. Ensure Object Lock does not retain objects under this
   prefix, because locked versions cannot be removed by the rule. Lifecycle
   processing is asynchronous, so allow at least one daily processing cycle.
   See [Backblaze's Lifecycle Rule guide][b2-lifecycle].
5. Create a separate Cloudflare API token with only **Cache Purge** permission,
   scoped only to the `crabcraft.net` zone. Record that zone's ID as well; do
   not grant this token account-wide access or B2 permissions.
6. In **Images > Transformations**, select the `crabcraft.net` zone and enable
   Image Transformations, as described in
   [Cloudflare's zone setup guide][cf-transformations]. If source-origin
   restrictions are configured, allow `cdn.crabcraft.net` as a transformation
   source.
7. Configure Cloudflare caching for `/gallery/*`. Because object keys include
   immutable Discord attachment IDs, these responses may be cached for a long
   period. Also add `X-Content-Type-Options: nosniff` on the CDN response.

Add this configuration to the bot's production Infisical environment:

```env
GALLERY_S3_ENDPOINT=https://s3.eu-central-003.backblazeb2.com
GALLERY_S3_ACCESS_KEY_ID=
GALLERY_S3_SECRET_ACCESS_KEY=
GALLERY_S3_BUCKET=YOUR_EXISTING_BUCKET_NAME
GALLERY_S3_REGION=eu-central-003
GALLERY_MEDIA_BASE_URL=https://cdn.crabcraft.net
GALLERY_CLOUDFLARE_ZONE_ID=
GALLERY_CLOUDFLARE_CACHE_PURGE_TOKEN=
```

Replace the example endpoint and region with the values shown for the real B2
bucket. The bot uploads, checks, and deletes objects through that authenticated
endpoint, then stores public URLs beneath `cdn.crabcraft.net`. Both Cloudflare
purge variables are mandatory when Gallery storage is enabled. The zone ID
belongs to the `crabcraft.net` zone, while the purge token is the narrowly
scoped token from step 5; neither value is a B2 S3 credential.

Inject the same `GALLERY_MEDIA_BASE_URL=https://cdn.crabcraft.net` entry into
both the bot and web deployments. It is public configuration rather than a
secret, but it deliberately has no `NEXT_PUBLIC_` counterpart: the web server
constructs Gallery media URLs and passes completed URLs to client components.
The remaining `GALLERY_S3_*` and `GALLERY_CLOUDFLARE_*` entries are bot-only;
never expose them to the web deployment or browser bundle. Supply
`GALLERY_MEDIA_BASE_URL` to both the web build and runtime so server rendering
and cached build output use the same origin.

### Removed and moderated images

An accepted Discord edit or deletion immediately removes the image reference
from the public Gallery and places its immutable storage key in a PostgreSQL
deletion queue with a five-minute grace period. An accepted upsert cancels a
queued deletion if the same key becomes current again. Stale or rejected sync
uploads are only queued when unreferenced; they are never deleted directly.

Before the bot checks B2 or uploads a new object, it durably reserves every
currently unreferenced storage key and its validated public URL in that same
queue for 30 minutes. The accepted post upsert cancels those reservations in
the same database transaction that publishes the image. If the bot crashes
after a B2 upload but before that upsert, the reservation survives the process
and the normal queue worker removes the orphaned object. A handled upload or
persistence failure requeues every object known to have been stored with the
usual five-minute cleanup grace. Reservations for keys never uploaded simply
expire after 30 minutes and produce an idempotent delete attempt.

The bot checks the durable queue on startup and every 60 seconds, claiming up
to 25 entries with a five-minute lease. It revalidates each claim immediately
before deleting the B2 object, then purges that exact public URL from
Cloudflare's edge cache. The queue entry is completed only after both actions
succeed, so removed or moderated media cannot remain available for the rest of
a long cache TTL. Crashes leave an expired lease retryable; failures retry after
30 seconds with exponential backoff capped at one hour.

Gallery previews use Cloudflare's `/cdn-cgi/image/` transformation URLs. Do not
try to purge those optimized URLs separately: Cloudflare does not support
purging individual `/cdn-cgi/` variants. The deletion contract remains a
single-file purge of the queued, original
`https://cdn.crabcraft.net/gallery/...` source URL; purging that
source also invalidates all of its optimized variants, according to
[Cloudflare's transformation cache guidance][cf-transformation-cache].

## 3. Deploy the database schema

Back up the production database first. Before using Drizzle, perform the
repository's required parity audit: compare every Java `CREATE TABLE` under
`apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/db/`
with `packages/db/src/schema.ts`, and resolve any drift. In particular, confirm
that the proposed change only adds the Gallery tables and intended indexes and
constraints.

With the production `DATABASE_URL` loaded, apply the reviewed schema:

```sh
bun run db:push
```

Do not run `db:push` against production until the parity audit and backup are
complete.

## 4. Deploy and backfill

Deploy the web and bot releases, then restart the bot so it loads the new
channel mapping and B2 credentials. Confirm the bot starts without Gallery
configuration, Discord permission, database, or B2 errors before starting the
historical import.

Preview the one-off backfill from the repository root. With no `--season`, it
checks every configured season:

```sh
bun run --cwd apps/bot gallery:backfill -- --dry-run
```

If the preview is correct, import all mapped seasons:

```sh
bun run --cwd apps/bot gallery:backfill
```

To import or retry one season only, pass its number; for example:

```sh
bun run --cwd apps/bot gallery:backfill -- --season 7
```

The backfill is designed to be repeatable: Discord thread and attachment IDs
are stable keys, so rerunning it reconciles existing rows instead of creating
duplicates. It has no resume token; rerun the affected season after a failure.
Use `bun run --cwd apps/bot gallery:backfill -- --help` for all options. Leave
the bot running during the import so new and edited posts are still handled by
the live event listeners. The CLI consumes one Discord Gateway session start;
if the application's session-start allowance is exhausted, it fails visibly
before changing data.

## 5. Verify the release

- Confirm `/gallery` lists real posts from all seven seasons, tag filters show
  the channel-defined labels and emoji, and each post detail page loads every
  image from `cdn.crabcraft.net`.
- In production, confirm Gallery preview requests use `/cdn-cgi/image/`
  variants rather than loading each full-resolution source into the card.
- Create a test post, then edit its text, tags, and attachments. Check that each
  change reaches the website. Delete the post and confirm it is unpublished
  immediately. After the five-minute storage grace and the next queue poll,
  confirm neither its former source URL nor a previously requested transformed
  preview URL returns the image.
- Check the bot logs for failed downloads, B2 writes, Discord rate limits, or
  database transactions. Inspect any retained deletion failures with:

  ```sql
  SELECT storage_key, delete_after, attempts, last_attempt_at, last_error
  FROM gallery_storage_deletions
  ORDER BY delete_after;
  ```
- Confirm no published image still points at Discord's expiring attachment CDN:

  ```sql
  SELECT discord_attachment_id, public_url
  FROM gallery_images
  WHERE public_url LIKE '%discordapp.com/attachments%';
  ```

- Compare per-season counts with Discord and spot-check a one-image and a
  multi-image post. Check the Gallery URLs in `/sitemap.xml` after the web cache
  refreshes.

## Rollback

Roll back the bot and web releases together, or stop the bot before restoring
the previous release so it cannot write a newer schema unexpectedly. Do not
drop the Gallery tables or delete B2 objects during an application rollback;
they are additive and retaining them makes recovery safe. Restore the database
backup only if the schema deployment itself caused damage, because restoring it
also discards data written after the backup. If credentials may have leaked,
revoke the affected B2 application key or Cloudflare purge token immediately
and issue a replacement.

[b2-app-keys]: https://www.backblaze.com/docs/cloud-storage-s3-compatible-app-keys
[b2-s3-endpoint]: https://www.backblaze.com/docs/en/cloud-storage-call-the-s3-compatible-api
[b2-cloudflare]: https://www.backblaze.com/docs/cloud-storage-deliver-public-backblaze-b2-content-through-cloudflare-cdn
[b2-lifecycle]: https://www.backblaze.com/docs/en/cloud-storage-lifecycle-rules
[cf-transformations]: https://developers.cloudflare.com/images/optimization/transformations/overview/#how-it-works
[cf-transformation-cache]: https://developers.cloudflare.com/images/optimization/features/#caching
