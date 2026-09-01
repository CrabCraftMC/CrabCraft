#!/bin/sh

set -eu

export INFISICAL_DISABLE_UPDATE_CHECK=true

has_infisical_config=false
for variable in \
  INFISICAL_CLIENT_ID \
  INFISICAL_CLIENT_SECRET \
  INFISICAL_PROJECT_ID \
  INFISICAL_ENV
do
  if [ -n "$(printenv "$variable" 2>/dev/null || true)" ]; then
    has_infisical_config=true
  fi
done

if [ "$has_infisical_config" = true ]; then
  for variable in \
    INFISICAL_CLIENT_ID \
    INFISICAL_CLIENT_SECRET \
    INFISICAL_PROJECT_ID \
    INFISICAL_ENV
  do
    if [ -z "$(printenv "$variable" 2>/dev/null || true)" ]; then
      echo "[CrabCraft] FATAL: incomplete Infisical configuration; missing $variable" >&2
      exit 1
    fi
  done

  echo "[CrabCraft] Authenticating with Infisical (universal auth)..."
  if [ -n "${INFISICAL_DOMAIN:-}" ]; then
    export INFISICAL_API_URL="$INFISICAL_DOMAIN"
    INFISICAL_TOKEN="$(infisical login \
      --method=universal-auth \
      --client-id="$INFISICAL_CLIENT_ID" \
      --client-secret="$INFISICAL_CLIENT_SECRET" \
      --domain="$INFISICAL_DOMAIN" \
      --silent \
      --plain)"
  else
    INFISICAL_TOKEN="$(infisical login \
      --method=universal-auth \
      --client-id="$INFISICAL_CLIENT_ID" \
      --client-secret="$INFISICAL_CLIENT_SECRET" \
      --silent \
      --plain)"
  fi
  export INFISICAL_TOKEN

  echo "[CrabCraft] Launching web app with Infisical (project=$INFISICAL_PROJECT_ID, env=$INFISICAL_ENV)..."
  exec infisical run \
    --projectId="$INFISICAL_PROJECT_ID" \
    --env="$INFISICAL_ENV" \
    -- node apps/web/server.js
fi

echo "[CrabCraft] Launching web app with directly supplied runtime environment..."
exec node apps/web/server.js
