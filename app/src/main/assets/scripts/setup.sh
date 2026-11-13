#!/bin/sh
set -eu

export DEBIAN_FRONTEND=noninteractive
export HOME=/home/opencode
export PATH="$HOME/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LD_LIBRARY_PATH="/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"
export TMPDIR="$HOME/.cache"
mkdir -p "$TMPDIR"

printf '[PKG] Updating Debian package lists…\n'
apt-get update
printf '[PKG] Installing the Linux and Node.js prerequisites…\n'
apt-get install -y -o Acquire::Retries=3 --no-install-recommends \
    ca-certificates curl git openssh-client procps util-linux \
    less nano ripgrep tzdata locales libatomic1 nodejs npm

if ! command -v npm >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1; then
    printf '[ERROR] Debian did not provide Node.js and npm.\n' >&2
    exit 1
fi

mkdir -p "$HOME/.config/opencode" "$HOME/.local/share/opencode" \
    "$HOME/.local/state/opencode" "$HOME/workspace" "$HOME/.cache"

printf '[OPENCODE] Installing OpenCode CLI using official installer…\n'
curl -fsSL https://opencode.ai/v2/install | bash

# Create bashrc for persistent PATH
cat > "$HOME/.bashrc" << 'EOF'
# OpenCode Android environment
export PATH="$HOME/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LD_LIBRARY_PATH="/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"
export TMPDIR="$HOME/.cache"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

cat > "$HOME/.profile" << 'EOF'
# OpenCode Android environment
export PATH="$HOME/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LD_LIBRARY_PATH="/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"
export TMPDIR="$HOME/.cache"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

export PATH="$HOME/.opencode/bin:$PATH"
command -v opencode >/dev/null
opencode --version
printf '[DONE] OpenCode is ready. Configure a provider in OpenCode when prompted.\n'
