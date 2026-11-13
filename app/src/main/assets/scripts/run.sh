#!/bin/sh
set -eu

export HOME=/home/opencode
export PATH="$HOME/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LD_LIBRARY_PATH="/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"
export TMPDIR="$HOME/.cache"
mkdir -p "$TMPDIR"
cd "$HOME/workspace"

MODE="${1:-tui}"
case "$MODE" in
    web)
        if [ -r /opt/loader/web-auth.env ]; then
            set -a
            . /opt/loader/web-auth.env
            set +a
        fi
        printf '[WEB] Starting OpenCode on 127.0.0.1:%s\n' "${OPENCODE_PORT:-4096}"
        exec opencode serve --hostname 127.0.0.1 --port "${OPENCODE_PORT:-4096}"
        ;;
    shell)
        exec /bin/bash -l
        ;;
    update)
        printf '[OPENCODE] Updating the official CLI package…\n'
        curl -fsSL https://opencode.ai/v2/install | bash
        opencode --version
        ;;
    tui)
        exec opencode
        ;;
    *)
        exec opencode "$@"
        ;;
esac
