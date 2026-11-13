#!/bin/sh
set -eu

MODE="${1:-boot}"
export HOME=/home/opencode
export PATH="$HOME/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LD_LIBRARY_PATH="/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"
export TMPDIR="$HOME/.cache"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TERM="${TERM:-xterm-256color}"

case "$MODE" in
    install)
        exec /bin/sh /opt/loader/setup.sh
        ;;
    boot|boot-fast)
        exec /bin/sh /opt/loader/run.sh
        ;;
    web)
        exec /bin/sh /opt/loader/run.sh web
        ;;
    shell)
        exec /bin/bash -l
        ;;
    update)
        exec /bin/sh /opt/loader/run.sh update
        ;;
    *)
        printf '[ERROR] Unknown runtime mode: %s\n' "$MODE" >&2
        exit 2
        ;;
esac
