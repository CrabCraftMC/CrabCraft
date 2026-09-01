# Product analytics

CrabCraft uses PostHog to understand the path from discovering the community
to becoming an active player. PostgreSQL remains the source of truth; PostHog
is a best-effort analysis layer and must never be used to decide membership,
permissions, moderation, rewards, or other gameplay state.

## Architecture

Events are emitted from three places:

| Runtime | SDK | Responsibility |
| --- | --- | --- |
| Website | `posthog-js` | Page views, tool outcomes, Wrapped completion, server-address copies, privacy-masked replay |
| Discord bot | `posthog-node` | Applications, review outcomes, linked command use, gallery posts, bingo progress |
| Velocity | `posthog-server` | Joins, sessions, server switches, streak qualification, settings changes, feature flags |

All server SDKs are buffered and fail open. A PostHog outage can lose analytics
events, but cannot block a command, application, join, settings update, or proxy
shutdown. Bot and proxy queues are flushed during graceful shutdown.

### Identity

The three runtimes derive one stable distinct ID from the canonical Minecraft
UUID:

```text
cc_ + hex(HMAC-SHA256(person_salt, "minecraft:" + lowercase_uuid_without_dashes))
```

The raw Minecraft UUID, username, Discord ID, IP address, and nickname are never
sent to PostHog. The secret `person_salt` must be identical in all three
runtimes. It is not a PostHog key and must not be exposed through a
`NEXT_PUBLIC_` environment variable.

Changing the salt permanently breaks historical identity continuity. Treat a
rotation as a deliberate analytics reset. A fixed regression vector in both
Java and TypeScript prevents either implementation from silently diverging.

Anonymous website visits use PostHog's browser-generated identity. When a
visitor signs in with a linked Minecraft account, the browser identifies as the
pseudonymous `cc_…` value. Signing out resets browser identity.

## Privacy contract

Do not add any of the following to event names, properties, deduplication keys,
person properties, groups, exception messages, or feature-flag properties:

- chat, direct messages, ticket or application answers, denial reasons, or
  gallery captions;
- usernames, nicknames, raw Minecraft UUIDs, Discord IDs, IP addresses, email
  addresses, webhook URLs, or access tokens;
- world coordinates, inventories, message IDs, channel IDs, or free-form user
  input.

The browser SDK disables DOM autocapture, element attributes, element text, and
browser IP collection. Before any browser event is sent, dynamic player and
gallery route identifiers are replaced with route templates, query strings and
fragments are removed, external referrers are reduced to their origin, and page
titles and campaign identifiers are discarded. Session replay is off by
default. If explicitly enabled, all input values and rendered text are masked,
and network requests and replay URLs are discarded. Server events disable GeoIP
and person-profile processing.

Event properties should be low-cardinality product facts: season, tool,
command name, backend name, boolean outcome, duration, setting key/value, or
aggregate counts. Deduplication IDs are hashed before they leave a server.

## Configuration

Create one PostHog project and use its project token and ingestion host in all
three runtimes. The examples default to the EU cloud host. Generate the shared
salt with a cryptographically secure password generator; use at least 32 random
bytes.

### Website

Set these in the web deployment environment:

```dotenv
NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN=phc_...
NEXT_PUBLIC_POSTHOG_HOST=https://eu.i.posthog.com
NEXT_PUBLIC_POSTHOG_ENVIRONMENT=production
NEXT_PUBLIC_POSTHOG_SESSION_REPLAY=false
POSTHOG_PERSON_SALT=<shared-secret>
```

The public project token and host are safe to expose in a browser. The person
salt is server-only. Leave the token empty to disable browser analytics. Enable
replay only after the privacy configuration has been verified against a
production-like page.

`NEXT_PUBLIC_` values are compiled into the browser bundle. For the production
image, configure `NEXT_PUBLIC_POSTHOG_PROJECT_TOKEN`,
`NEXT_PUBLIC_POSTHOG_HOST`, and optionally
`NEXT_PUBLIC_POSTHOG_SESSION_REPLAY` as variables on the GitHub `release`
environment before the image is built.

The production container can fetch `POSTHOG_PERSON_SALT` from Infisical at
startup. Store the salt in the selected Infisical project/environment and put
only these universal-auth bootstrap values in `apps/web/.env` on the host:

```dotenv
INFISICAL_CLIENT_ID=...
INFISICAL_CLIENT_SECRET=...
INFISICAL_PROJECT_ID=...
INFISICAL_ENV=production
# INFISICAL_DOMAIN=https://your-infisical.example.com  # optional
```

If those values are absent, the container falls back to directly supplied
runtime environment variables. Never expose `POSTHOG_PERSON_SALT` as a build
argument or `NEXT_PUBLIC_` value. For a local build, put all of the PostHog
values above directly in `apps/web/.env` before running the build.

### Discord bot

Set these in `apps/bot/.env` or the bot's secret store:

```dotenv
POSTHOG_PROJECT_TOKEN=phc_...
POSTHOG_HOST=https://eu.i.posthog.com
POSTHOG_PERSON_SALT=<shared-secret>
POSTHOG_ENVIRONMENT=production
```

Both the token and salt are required. If only one is present, analytics stays
disabled and the bot logs a warning.

### Velocity

Configure `plugins/CrabUtilities/config.yml`:

```yaml
analytics:
  enabled: true
  project-token: "phc_..."
  host: "https://eu.i.posthog.com"
  person-salt: "<shared-secret>"
  environment: "production"
```

The proxy logs whether analytics is enabled. `/crabutilities reload` replaces
the SDK instance and flushes the old queue.

Use distinct PostHog projects for development and production where possible.
If one project is shared, every custom event includes an `environment`
property that must be applied to insights and dashboards.

## Event catalogue

| Event | Runtime | Properties |
| --- | --- | --- |
| `application submitted` | Bot | `season`, `voice_chat_opt_in` |
| `application resolved` | Bot | `outcome`, `review_duration_seconds`, `season` |
| `bingo square completed` | Bot | `card_id`, `task_id`, `source_backend` |
| `discord command completed` | Bot | `command`, `duration_ms`, `success` |
| `gallery post published` | Bot | `season` |
| `player joined` | Velocity | `backend_server`, `first_join`, `season` |
| `player session ended` | Velocity | `duration_seconds`, `last_server`; proxy shutdown adds `disconnect_reason` |
| `server switched` | Velocity | `from_server`, `to_server` |
| `login day qualified` | Velocity | `current_streak`, `longest_streak`, `season` |
| `player setting changed` | Velocity | `setting`, `value` |
| `server address copied` | Web | `location` |
| `web tool completed` | Web | `tool`, `action`, and safe aggregate tool properties |
| `wrapped completed` | Web | `season` |

Standard properties added by CrabCraft are `source` and `environment`. PostHog
also emits its normal SDK metadata and browser page-view properties.

## Recommended insights

Start with a small set of decision-oriented dashboards:

1. Website intent: application-page view → `server address copied`, split by
   referrer and landing page.
2. Onboarding funnel: `application submitted` → `application resolved` where
   outcome is accepted → `player joined`.
3. Activation: accepted players who join within 1, 3, and 7 days, then reach a
   30-minute `player session ended` event.
4. Retention: weekly retention based on `player joined`, split by season and
   first join.
5. Community engagement: unique players completing bingo squares, publishing
   gallery posts, completing Wrapped, and using linked Discord commands.
6. Server movement: paths over `server switched`, using `from_server` and
   `to_server` to find confusing or underused backends.
7. Tool value: `web tool completed` by tool/action, with page-view-to-completion
   conversion.
8. Operations: application acceptance rate and review-duration percentiles.

Do not use raw event volume as a proxy for unique people. Prefer unique
pseudonymous users, cohorts, funnels, and retention.

Pre-membership website activity is intentionally anonymous. PostHog can merge
that history if the same browser is later identified after account linking, but
it cannot reliably connect a different browser or device to the Discord
application. Keep website-intent and onboarding reporting separate unless that
identity limitation is acceptable.

## Feature flags and experiments

The browser helper and Velocity service expose feature-flag checks. Flags are
for reversible presentation or gameplay experiments whose default behaviour is
safe. A missing PostHog configuration, timeout, or SDK failure must use the
local fallback.

Never gate permissions, whitelist status, moderation, purchases, data access,
or irreversible progression on PostHog. Roll out one change at a time, define
the success metric before enabling it, and keep a local kill switch. Avoid
putting identifying or high-cardinality values into flag properties.

## Rollout and validation

1. Deploy with all tokens empty and confirm every runtime remains functional.
2. Configure a development PostHog project and use the same test salt in all
   runtimes.
3. Link one test player, visit the website, run a Discord command, and join the
   proxy. Confirm all events share one `cc_…` distinct ID.
4. Inspect browser and server payloads. Confirm there are no names, raw IDs,
   coordinates, content, or IP/GeoIP properties.
5. Verify application resolution and bingo events are deduplicated after a
   retry or duplicate delivery.
6. Build the dashboards above, add an `environment = production` filter, then
   enable production ingestion.
7. Only then consider session replay. Verify masking manually before setting
   `NEXT_PUBLIC_POSTHOG_SESSION_REPLAY=true`.

Useful local checks:

```sh
bun test apps/bot/tests/analyticsIdentity.test.ts
bun run --cwd apps/bot build
bun run --cwd apps/web build
cd apps/minecraft && ./gradlew check
```

When adding an event, update the shared TypeScript catalogue where applicable,
this document, and a regression test for any new identity or deduplication
behaviour.
