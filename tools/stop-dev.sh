#!/usr/bin/env bash
#
# Tear down the dev stack.
#
#   ./stop-dev.sh            kill the tmux session (stops every service in it)
#   ./stop-dev.sh --all      also stop the dev databases and the Gradle daemon
#
set -uo pipefail

SESSION=mdeo-dev
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmux kill-session -t "$SESSION" 2>/dev/null

if [[ ${1:-} == --all ]]; then
    docker compose -f "$ROOT/infra/docker-compose-dev.yaml" stop
    (cd "$ROOT/platform" && ./gradlew --stop)
fi
