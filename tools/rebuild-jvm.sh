#!/usr/bin/env bash
#
# Rebuild all JVM platform services and restart them in the dev session.
#
#   ./rebuild-jvm.sh           stop the JVM panes, run one Gradle build, start them again
#   ./rebuild-jvm.sh --hold    same, for running in its own tmux window: closes itself on
#                              success, waits for Enter on failure so the error stays readable
#
# Also bound in the dev session: click "rebuild JVM" in the status bar, or press prefix + B.
# Without a running session it just builds (run-dev.sh uses it for its preflight).
#
# Gradle cannot watch, and one build per service in parallel makes the builds conflict
# over the shared modules - so this is the one place the platform gets built.
#
set -uo pipefail

SESSION=mdeo-dev

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="$(cd "$TOOLS/../platform" && pwd)"
LOCK_FILE="${TMPDIR:-/tmp}/mdeo-dev-gradle.lock"

HOLD=0
[[ ${1:-} == --hold ]] && HOLD=1

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

finish() { # finish <exit-code>
    if (( HOLD )); then
        if (( $1 == 0 )); then
            # Closing this window returns to the one the rebuild was started from.
            sleep 1
        else
            read -r -p "Press Enter to close. " _
        fi
    fi
    exit "$1"
}

# One rebuild at a time - a second click while one is running does nothing.
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "A rebuild is already running."
    finish 1
fi

# JVM panes are tagged by run-dev.sh with the command that starts them (@jvm-cmd) and
# the name of their idle shell (@idle-cmd), so we can tell when a service has stopped.
PANES=()
if tmux has-session -t "$SESSION" 2>/dev/null; then
    while read -r id; do
        PANES+=("$id")
    done < <(tmux list-panes -s -t "$SESSION" -F '#{pane_id} #{@jvm-cmd}' | awk 'NF > 1 { print $1 }')
fi

pane_running() { # pane_running <pane>  -> true while something other than the shell runs
    local cur idle
    cur=$(tmux display-message -p -t "$1" '#{pane_current_command}')
    idle=$(tmux display-message -p -t "$1" '#{@idle-cmd}')
    [[ $cur != "$idle" ]]
}

STOPPED=()
if (( ${#PANES[@]} )); then
    step "Stopping ${#PANES[@]} JVM services"
    for p in "${PANES[@]}"; do
        pane_running "$p" && tmux send-keys -t "$p" C-c
    done
    for _ in $(seq 1 60); do
        still=0
        for p in "${PANES[@]}"; do pane_running "$p" && still=1; done
        (( still )) || break
        sleep 0.5
    done
    for p in "${PANES[@]}"; do
        if pane_running "$p"; then
            echo "  $(tmux display-message -p -t "$p" '#{pane_title}') did not stop - leaving it alone"
        else
            STOPPED+=("$p")
        fi
    done
fi

step "Building platform services"
(cd "$PLATFORM" && ./gradlew --console=plain \
    :backend:installDist \
    :script-execution:installDist \
    :model-transformation-execution:installDist \
    :optimizer-execution:installDist) 9>&-  # keep the lock out of the Gradle daemon
rc=$?

if (( ${#STOPPED[@]} )); then
    if (( rc == 0 )); then
        step "Starting ${#STOPPED[@]} JVM services"
        for p in "${STOPPED[@]}"; do
            tmux send-keys -t "$p" "$(tmux display-message -p -t "$p" '#{@jvm-cmd}')" C-m
        done
    else
        echo
        echo "Build failed - the JVM services stay stopped. Fix the build and rebuild again."
    fi
fi

finish "$rc"
