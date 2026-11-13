#!/bin/bash
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -o Acquire::Retries=3 --no-install-recommends)

printf '[PKG] Updating Debian package lists…\n'
apt-get update
printf '[PKG] Installing the Linux and Node.js prerequisites…\n'
apt-get install "${APT_OPTS[@]}" \
    ca-certificates curl git openssh-client procps util-linux \
    less nano ripgrep tzdata locales libatomic1 nodejs npm

if ! command -v npm >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1; then
    printf '[ERROR] Debian did not provide Node.js and npm.\n' >&2
    exit 1
fi

mkdir -p "$HOME/.config/opencode" "$HOME/.local/share/opencode" \
    "$HOME/.local/state/opencode" "$HOME/workspace" "$HOME/.cache"

printf '[OPENCODE] Installing the official OpenCode CLI package…\n'
npm install --global --no-audit --no-fund @opencode/cli@2.3.0
command -v opencode >/dev/null
opencode --version
printf '[DONE] OpenCode is ready. Configure a provider in OpenCode when prompted.\n'
