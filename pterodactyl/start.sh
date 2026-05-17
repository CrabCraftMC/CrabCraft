#!/usr/bin/env bash
# Pterodactyl startup wrapper for the CrabCraft Discord bot.
# Invoked from the egg's `startup` field. Reads:
#   - GIT_REPO_URL, GIT_REF, AUTO_UPDATE
#   - GITHUB_TOKEN (used for cloning/pulling private mirrors)
#   - INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET, INFISICAL_PROJECT_ID,
#     INFISICAL_ENV, INFISICAL_DOMAIN (optional, defaults to Infisical Cloud)
# Bot secrets (Discord token, database URLs, API keys) are pulled from
# Infisical at launch and injected into the bot's environment via
# `infisical run`. Role/channel IDs live in apps/bot/config.json and are
# managed manually (panel file editor / SFTP).

set -e

cd /home/container
export PATH="/home/container/.local/bin:${PATH}"

# Plumb GITHUB_TOKEN into git via the bundled askpass helper, so the token
# never lands in .git/config or argv.
if [ -n "${GITHUB_TOKEN:-}" ] && [ -x /home/container/.local/bin/git-askpass.sh ]; then
    export GIT_ASKPASS=/home/container/.local/bin/git-askpass.sh
    export GIT_TERMINAL_PROMPT=0
fi

if [ "${AUTO_UPDATE:-1}" = "1" ]; then
    echo "[CrabCraft] Updating to ${GIT_REF:-main}..."
    if git remote set-url origin "${GIT_REPO_URL:-https://github.com/CrabCraftMC/CrabCraft.git}" \
        && git fetch --depth 1 origin "${GIT_REF:-main}" \
        && git reset --hard FETCH_HEAD \
        && bun install --frozen-lockfile --production; then
        echo "[CrabCraft] Update OK."
    else
        echo "[CrabCraft] Update failed; starting with current version."
    fi
fi

missing=()
for v in INFISICAL_CLIENT_ID INFISICAL_CLIENT_SECRET INFISICAL_PROJECT_ID INFISICAL_ENV; do
    if [ -z "${!v:-}" ]; then
        missing+=("${v}")
    fi
done
if [ "${#missing[@]}" -gt 0 ]; then
    echo "[CrabCraft] FATAL: missing required variables: ${missing[*]}" >&2
    exit 1
fi

if ! command -v infisical >/dev/null 2>&1; then
    echo "[CrabCraft] FATAL: infisical CLI not found on PATH. Reinstall the server to repopulate /home/container/.local/bin." >&2
    exit 1
fi

login_domain_args=()
if [ -n "${INFISICAL_DOMAIN:-}" ]; then
    # `infisical login` takes --domain explicitly; for all later commands
    # we route through INFISICAL_API_URL per the CLI docs.
    login_domain_args=(--domain="${INFISICAL_DOMAIN}")
    export INFISICAL_API_URL="${INFISICAL_DOMAIN}"
fi

echo "[CrabCraft] Authenticating with Infisical (universal auth)..."
INFISICAL_TOKEN="$(infisical login \
    --method=universal-auth \
    --client-id="${INFISICAL_CLIENT_ID}" \
    --client-secret="${INFISICAL_CLIENT_SECRET}" \
    --silent \
    --plain \
    "${login_domain_args[@]}")"
export INFISICAL_TOKEN

echo "[CrabCraft] Launching bot (project=${INFISICAL_PROJECT_ID}, env=${INFISICAL_ENV})..."
cd /home/container/apps/bot
exec infisical run \
    --projectId="${INFISICAL_PROJECT_ID}" \
    --env="${INFISICAL_ENV}" \
    -- bun run src/index.ts
