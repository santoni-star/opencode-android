#!/bin/bash

say() {
    printf '\033[1;36m[%s]\033[0m %s\n' "$1" "$2"
}

warn() {
    printf '\033[1;33m[%s]\033[0m %s\n' "$1" "$2"
}

die() {
    printf '\033[1;31m[ERROR]\033[0m %s\n' "$1" >&2
    exit 1
}
