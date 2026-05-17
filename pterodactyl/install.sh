#!/usr/bin/env bash
# Real install logic for the CrabCraft Bot egg. The bootstrap in the
# egg's `installation.script` field clones the repo into /mnt/server and
# execs this. Edits to this file propagate via git on the next reinstall —
# no need to re-import the egg.

set -e

cd /mnt/server

# Bun's installer extracts ~100MB into $BUN_INSTALL/bin. The installer
# container's /tmp is a small tmpfs and overflows, so target the
# persistent server volume instead.
export BUN_INSTALL="/mnt/server/.local"
echo "==> Installing Bun to ${BUN_INSTALL}"
curl -fsSL https://bun.sh/install | bash
export PATH="${BUN_INSTALL}/bin:${PATH}"
bun --version

echo "==> Installing dependencies"
bun install --frozen-lockfile --production

echo "==> Installing Infisical CLI"
curl -1sLf 'https://artifacts-cli.infisical.com/setup.deb.sh' | bash
apt-get update
apt-get install -y infisical
mkdir -p /mnt/server/.local/bin
cp "$(command -v infisical)" /mnt/server/.local/bin/infisical
chmod +x /mnt/server/.local/bin/infisical
/mnt/server/.local/bin/infisical --version

if [ -f pterodactyl/start.sh ]; then
    chmod +x pterodactyl/start.sh
else
    echo "WARNING: pterodactyl/start.sh missing from repo; the server will fail to start." >&2
fi

echo "==> Install complete"
