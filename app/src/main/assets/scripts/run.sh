#!/bin/bash
set -euo pipefail

export HOME=/home/opencode
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
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
        npm install --global --no-audit --no-fund @opencode/cli@latest
        opencode --version
        ;;
    tui)
        exec opencode
        ;;
    *)
        exec opencode "$@"
        ;;
esac
