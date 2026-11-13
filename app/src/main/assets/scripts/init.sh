#!/bin/bash
set -euo pipefail

MODE="${1:-boot}"
export HOME=/home/opencode
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TERM="${TERM:-xterm-256color}"

case "$MODE" in
    install)
        exec /bin/bash /opt/loader/setup.sh
        ;;
    boot|boot-fast)
        exec /bin/bash /opt/loader/run.sh
        ;;
    web)
        exec /bin/bash /opt/loader/run.sh web
        ;;
    shell)
        exec /bin/bash -l
        ;;
    update)
        exec /bin/bash /opt/loader/run.sh update
        ;;
    *)
        printf '[ERROR] Unknown runtime mode: %s\n' "$MODE" >&2
        exit 2
        ;;
esac
