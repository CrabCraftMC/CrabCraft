#!/usr/bin/env bash
# Pterodactyl startup wrapper for the CrabCraft Discord bot.
# Invoked from the egg's `startup` field. Reads:
#   - GIT_REPO_URL, GIT_REF, AUTO_UPDATE
#   - INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET, INFISICAL_PROJECT_ID,
#     INFISICAL_ENV, INFISICAL_DOMAIN (optional, defaults to Infisical Cloud)
# Discord/database/API secrets are pulled from Infisical at launch and
# injected into the bot's environment via `infisical run`.

set -e

cd /home/container
export PATH="/home/container/.local/bin:${PATH}"

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

domain_args=()
if [ -n "${INFISICAL_DOMAIN:-}" ]; then
    domain_args=(--domain="${INFISICAL_DOMAIN}")
fi

echo "[CrabCraft] Authenticating with Infisical (universal auth)..."
INFISICAL_TOKEN="$(infisical login \
    --method=universal-auth \
    --client-id="${INFISICAL_CLIENT_ID}" \
    --client-secret="${INFISICAL_CLIENT_SECRET}" \
    --plain \
    "${domain_args[@]}")"
export INFISICAL_TOKEN

echo "[CrabCraft] Launching bot (project=${INFISICAL_PROJECT_ID}, env=${INFISICAL_ENV})..."
cd /home/container/apps/bot
exec infisical run \
    --projectId="${INFISICAL_PROJECT_ID}" \
    --env="${INFISICAL_ENV}" \
    "${domain_args[@]}" \
    -- bun run src/index.ts
