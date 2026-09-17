#!/usr/bin/env bash
#
# Bring up the whole dev stack in one tmux session, with one window per plugin:
#
#   core       tsc watch, the workbench dev server and the backend
#   <plugin>   the plugin's watchers and service, plus its JVM execution services
#              (script-execution, model-transformation-execution, and the three
#              optimizer nodes under config-mdeo)
#
# Switch windows with Ctrl-b n / Ctrl-b p, or click them in the status bar.
#
# To pick up Kotlin changes, click "rebuild JVM" in the status bar (or press
# Ctrl-b B, or run tools/rebuild-jvm.sh): it stops all JVM services, runs one
# Gradle build and starts them again.
#
#   ./run-dev.sh               databases + npm ci + build everything once + start all panes
#   ./run-dev.sh --no-install  skip `npm ci` (assumes node_modules is current)
#   ./run-dev.sh --no-build    skip the preflight builds (assumes a recent build)
#   ./run-dev.sh --fresh       stop stale Gradle daemons and drop platform/.gradle
#                              first - the fix when a build reports something absurd
#                              (empty jars, "unresolved reference" on a clean tree)
#   ./run-dev.sh --no-attach   set the session up but stay in this shell
#
# All building happens up front, serialized, in this terminal: `npm ci`, one `tsc -b`
# and ONE Gradle build for all JVM services. The panes then only watch (TypeScript)
# or start the installed launchers (JVM) - Gradle cannot watch anyway, and
# concurrent Gradle builds from several panes conflict with each other.
#
set -Eeuo pipefail

SESSION=mdeo-dev

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$TOOLS/.." && pwd)"
APP="$ROOT/app"
PKG="$APP/packages"
PLATFORM="$ROOT/platform"
REBUILD_JVM="$TOOLS/rebuild-jvm.sh"
COMPOSE_FILE="$ROOT/infra/docker-compose-dev.yaml"

# Extra JVM options for every platform service, e.g. MDEO_JAVA_OPTS=-Xmx1g to keep
# six JVMs from reserving a quarter of RAM each on a small machine. The Gradle
# start scripts append this to the module's own defaults, so --add-opens survives.
# Passed on each pane's command line, because a tmux server that is already running
# does not see this script's environment.
JAVA_OPTS="${MDEO_JAVA_OPTS:-}"

SKIP_INSTALL=0
SKIP_BUILD=0
FRESH=0
ATTACH=1
for arg in "$@"; do
    case "$arg" in
        --no-install) SKIP_INSTALL=1 ;;
        --no-build)   SKIP_BUILD=1 ;;
        --fresh)      FRESH=1 ;;
        --no-attach)  ATTACH=0 ;;
        -h|--help)    sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^#\s\?//'; exit 0 ;;
        *)            echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

command -v tmux >/dev/null || { echo "tmux is not installed" >&2; exit 1; }

# --- TEAR DOWN THE PREVIOUS RUN FIRST ---
#
# Before the preflight, not after: the old session holds six JVMs, ~20 node
# watchers and every service port, and leaving it up while a 2 GB Gradle daemon
# builds is how this machine ends up in swap (and `npm ci` would delete
# node_modules out from under the watchers). It also puts real time between
# killing the session and creating the new one - killing the last session takes
# the tmux server down with it, and talking to a server that is still shutting
# down is what produces "server exited unexpectedly".

if tmux has-session -t "$SESSION" 2>/dev/null; then
    step "Stopping the previous '$SESSION' session"
    tmux kill-session -t "$SESSION" 2>/dev/null || true
    for _ in $(seq 1 50); do
        tmux has-session -t "$SESSION" 2>/dev/null || break
        sleep 0.2
    done
fi

# --- PREFLIGHT (serialized, fails loudly before any pane is created) ---

DATABASES=(postgres-backend postgres-script postgres-model-transformation postgres-optimizer)

# The same compose file also runs the whole stack in containers (`up --build`, see
# local-development.md), and with `restart: unless-stopped` those containers come back
# on every boot and hold the ports the panes need. Stop everything but the databases.
mapfile -t containers < <(docker compose -f "$COMPOSE_FILE" ps --services --status running \
    | grep -vxF -f <(printf '%s\n' "${DATABASES[@]}") || true)
if (( ${#containers[@]} )); then
    step "Stopping containerized services: ${containers[*]}"
    docker compose -f "$COMPOSE_FILE" stop "${containers[@]}"
fi

# Anything else on a service port would only show up later as one failing pane among
# thirty, so check up front and name the ports. Retried for a few seconds, since the
# JVMs of a session killed just above may still be shutting down.
PORTS=(4242 3000 3001 3002 3003 3004 3005 3006 3007 3008 8080 8081 8082 8083 8084 8085)
for _ in $(seq 1 20); do
    busy=()
    for port in "${PORTS[@]}"; do
        if ss -Hltn "sport = :$port" | grep -q .; then busy+=("$port"); fi
    done
    (( ${#busy[@]} )) || break
    sleep 0.5
done
if (( ${#busy[@]} )); then
    echo "ports already in use: ${busy[*]}" >&2
    echo "find the owner with: sudo ss -ltnp 'sport = :${busy[0]}'" >&2
    exit 1
fi

step "Starting databases"
docker compose -f "$COMPOSE_FILE" up -d --wait "${DATABASES[@]}"

if (( SKIP_INSTALL )); then
    step "Skipping npm ci (--no-install)"
else
    step "Installing npm dependencies"
    (cd "$APP" && npm ci --no-audit --no-fund)
fi

if (( FRESH )); then
    step "Clearing Gradle daemons and project cache"
    (cd "$PLATFORM" && ./gradlew --stop) || true
    # platform/.gradle holds the incremental-build bookkeeping. It is what the old
    # parallel-`gradlew run` setup corrupted, and a corrupt entry survives `clean`:
    # tasks get reported UP-TO-DATE while their outputs are empty. Dropping it costs
    # one slower build and nothing else.
    rm -rf "$PLATFORM/.gradle"
fi

if (( SKIP_BUILD )); then
    step "Skipping preflight builds (--no-build)"
else
    step "Building TypeScript packages"
    (cd "$APP" && npm run build:packages)

    # No session is running at this point, so this only builds.
    "$REBUILD_JVM"
fi

# --- LAYOUT ---
#
# Declared first, built afterwards: `window` starts a tmux window, `column` starts a
# column in it, and `pane <title> <cwd> <command>` stacks a pane in that column.
# `jvm <title> <module> VAR=value...` adds a JVM service pane, which rebuild-jvm.sh
# stops and restarts.

W_NAME=()
P_WIN=() P_COL=() P_TITLE=() P_CWD=() P_CMD=() P_JVM=()

window() { W_NAME+=("$1"); NCOL=0; }
column() { NCOL=$(( NCOL + 1 )); }
pane() {
    P_WIN+=("$(( ${#W_NAME[@]} - 1 ))")
    P_COL+=("$(( NCOL - 1 ))")
    P_TITLE+=("$1") P_CWD+=("$2") P_CMD+=("$3") P_JVM+=(0)
}

jvm() {
    local title=$1 module=$2; shift 2
    pane "$title" "$PLATFORM" "$(printf '%q ' JAVA_OPTS="$JAVA_OPTS" "$@") $module/build/install/$module/bin/$module"
    P_JVM[-1]=1
}

optimizer() { # optimizer <port> <node-id> <peer-port> <peer-port>
    jvm "optimizer node $2 :$1" optimizer-execution \
        DATABASE_URL=jdbc:postgresql://localhost:5435/mdeo \
        DATABASE_USER=mdeo \
        DATABASE_PASSWORD=mdeo \
        BACKEND_API_URL=http://localhost:8080/api \
        SCRIPT_TIMEOUT_MS=1000 \
        TRANSFORMATION_TIMEOUT_MS=1000 \
        SERVER_PORT="$1" \
        NODE_ID="$2" \
        PEERS="http://localhost:$3,http://localhost:$4" \
        NODE_URL="http://localhost:$1" \
        WORKER_THREADS=2
}

window core
column
pane 'app: tsc watch'                "$APP" 'npm run watch'
pane 'workbench: vite dev'           "$APP" 'npm run dev'
column
jvm 'backend :8080' backend \
    DATABASE_URL=jdbc:postgresql://localhost:5432/mdeo \
    DATABASE_USER=mdeo \
    DATABASE_PASSWORD=mdeo \
    SESSION_MAX_IDLE_SECONDS=604800 \
    SESSION_MAX_ABSOLUTE_SECONDS=15552000 \
    CSRF_ROTATION_SECONDS=3600 \
    COOKIE_SECURE=false \
    COOKIE_SAMESITE=Lax \
    CORS_ALLOWED_HOSTS=localhost:4242,localhost:5173,127.0.0.1:4242,127.0.0.1:5173 \
    ADMIN_USERNAME=admin \
    ADMIN_PASSWORD=admin \
    SERVER_PORT=8080 \
    PLUGIN_BASE_URL=http://localhost:4242 \
    PLUGIN_FORCE_HTTP1=true

window metamodel
column
pane 'metamodel: css'                "$PKG/editor-metamodel"              'npm run watch:css'
pane 'metamodel: static'             "$PKG/service-metamodel"             'npm run watch:static'
pane 'metamodel :3000'               "$PKG/service-metamodel"             'PORT=3000 npm run watch'

window model
column
pane 'model: css'                    "$PKG/editor-model"                  'npm run watch:css'
pane 'model: static'                 "$PKG/service-model"                 'npm run watch:static'
pane 'model :3001'                   "$PKG/service-model"                 'PORT=3001 npm run watch'

window script
column
pane 'script: static'                "$PKG/service-script"                'npm run watch:static'
pane 'script :3002'                  "$PKG/service-script"                'PORT=3002 npm run watch'
column
jvm 'script-execution :8081' script-execution \
    DATABASE_URL=jdbc:postgresql://localhost:5433/mdeo \
    DATABASE_USER=mdeo \
    DATABASE_PASSWORD=mdeo \
    BACKEND_API_URL=http://localhost:8080/api \
    EXECUTION_TIMEOUT_MS=2000 \
    SERVER_PORT=8081

window model-transformation
column
pane 'model-transformation: css'     "$PKG/editor-model-transformation"   'npm run watch:css'
pane 'model-transformation: static'  "$PKG/service-model-transformation"  'npm run watch:static'
pane 'model-transformation :3003'    "$PKG/service-model-transformation"  'PORT=3003 npm run watch'
column
jvm 'model-transformation-execution :8082' model-transformation-execution \
    DATABASE_URL=jdbc:postgresql://localhost:5434/mdeo \
    DATABASE_USER=mdeo \
    DATABASE_PASSWORD=mdeo \
    BACKEND_API_URL=http://localhost:8080/api \
    SERVER_PORT=8082

window config
column
pane 'config: static'                "$PKG/service-config"                'npm run watch:static'
pane 'config :3004'                  "$PKG/service-config"                'PORT=3004 npm run watch'

window config-optimization
column
pane 'config-optimization: static'   "$PKG/service-config-optimization"   'npm run watch:static'
pane 'config-optimization :3005'     "$PKG/service-config-optimization"   'PORT=3005 npm run watch'

# The optimizer nodes serve config-mdeo's optimization runs.
window config-mdeo
column
pane 'config-mdeo: static'           "$PKG/service-config-mdeo"           'npm run watch:static'
pane 'config-mdeo :3006'             "$PKG/service-config-mdeo"           'PORT=3006 npm run watch'
column
optimizer 8083 0 8084 8085
optimizer 8084 1 8083 8085
optimizer 8085 2 8083 8084

window model-csv
column
pane 'model-csv: static'             "$PKG/service-model-csv"             'npm run watch:static'
pane 'model-csv :3007'               "$PKG/service-model-csv"             'PORT=3007 npm run watch'

window csv
column
pane 'csv: static'                   "$PKG/service-csv"                   'npm run watch:static'
pane 'csv :3008'                     "$PKG/service-csv"                   'PORT=3008 npm run watch'

# --- TMUX SESSION ---

step "Starting tmux session '$SESSION'"

tmux kill-session -t "$SESSION" 2>/dev/null || true

# Build the layout in a generously sized window so no split can fail with
# "pane too small"; tmux resizes the windows to whatever client attaches.
start_session() { # start_session <first-window-name> <cwd>  -> first pane id
    local attempt out
    for attempt in 1 2 3 4 5; do
        if out=$(tmux new-session -d -s "$SESSION" -n "$1" -c "$2" -x 240 -y 64 -P -F '#{pane_id}' 2>&1); then
            printf '%s' "$out"
            return 0
        fi
        echo "  tmux: ${out:-unknown error} (attempt $attempt/5)" >&2
        sleep 1
    done
    echo "could not start tmux session '$SESSION': ${out:-unknown error}" >&2
    echo "if this persists, run 'tmux kill-server' and try again" >&2
    return 1
}

# split <pane> <-h|-v> <cwd> <remaining>  -> new pane id
# Splits off a pane sized so that, repeated down a column/row, all parts end up equal:
# the new pane gets remaining/(remaining+1) of the space being split.
split() {
    tmux split-window "$2" -t "$1" -c "$3" -l "$(( $4 * 100 / ($4 + 1) ))%" -P -F '#{pane_id}'
}

# A tmux failure part way through leaves a half-built session behind; drop it so
# the next run starts from a clean slate instead of a confusing partial layout.
trap 'rc=$?; if (( rc )); then echo "run-dev: tmux setup failed (exit $rc); removing partial session" >&2; tmux kill-session -t "$SESSION" 2>/dev/null; fi' ERR

P_ID=()
for w in "${!W_NAME[@]}"; do
    # Panes of this window: the top pane of each column, and the number per column.
    tops=() per_col=()
    for i in "${!P_WIN[@]}"; do
        (( P_WIN[i] == w )) || continue
        c=${P_COL[i]}
        [[ -n ${tops[c]:-} ]] || tops[c]=$i
        per_col[c]=$(( ${per_col[c]:-0} + 1 ))
    done
    ncols=${#per_col[@]}

    # Columns first, while each is still a single full-height pane.
    first=${tops[0]}
    if (( w == 0 )); then
        P_ID[first]=$(start_session "${W_NAME[w]}" "${P_CWD[first]}")
        # Session-scoped, so running this script does not reconfigure the user's other sessions.
        tmux set-option -t "$SESSION" mouse on
        tmux set-option -t "$SESSION" pane-border-status top
        tmux set-option -t "$SESSION" pane-border-format ' #{pane_title} '
        # Status bar at the top; windows as tabs, the current one highlighted by its
        # background instead of tmux's default "*" marker.
        tmux set-option -t "$SESSION" status-position top
        tmux set-option -t "$SESSION" status-style 'bg=colour236,fg=colour250'
    else
        P_ID[first]=$(tmux new-window -d -t "$SESSION:" -n "${W_NAME[w]}" -c "${P_CWD[first]}" -P -F '#{pane_id}')
    fi
    for (( c = 1; c < ncols; c++ )); do
        i=${tops[c]}
        P_ID[i]=$(split "${P_ID[tops[c - 1]]}" -h "${P_CWD[i]}" $(( ncols - c )))
    done

    # Then the rows inside each column.
    for (( c = 0; c < ncols; c++ )); do
        prev=${tops[c]}
        left=$(( per_col[c] - 1 ))
        for (( i = prev + 1; left > 0; i++ )); do
            P_ID[i]=$(split "${P_ID[prev]}" -v "${P_CWD[i]}" "$left")
            prev=$i
            left=$(( left - 1 ))
        done
    done
done

# Tab styling is per window in tmux (a session-level set only reaches the first window).
for name in "${W_NAME[@]}"; do
    tmux set-option -w -t "$SESSION:$name" window-status-separator ''
    tmux set-option -w -t "$SESSION:$name" window-status-format ' #I #W '
    tmux set-option -w -t "$SESSION:$name" window-status-current-format ' #I #W '
    tmux set-option -w -t "$SESSION:$name" window-status-current-style 'bg=colour33,fg=colour231,bold'
done

# Tag JVM panes for rebuild-jvm.sh: the command that starts the service, and the
# name of the idle shell, which tells it when the service has stopped.
for i in "${!P_ID[@]}"; do
    tmux select-pane -t "${P_ID[i]}" -T "${P_TITLE[i]}"
    if (( P_JVM[i] )); then
        tmux set-option -p -t "${P_ID[i]}" @jvm-cmd "${P_CMD[i]}"
        tmux set-option -p -t "${P_ID[i]}" @idle-cmd "$(tmux display-message -p -t "${P_ID[i]}" '#{pane_current_command}')"
    fi
done

# The rebuild button: a clickable range in this session's status bar, plus prefix + B.
# Key bindings are server-wide, so both only act inside this session and otherwise
# keep tmux's default behavior. The rebuild runs in its own window to show its output.
REBUILD_WINDOW="new-window -t $SESSION: -n rebuild $(printf '%q' "$REBUILD_JVM") --hold"
tmux set-option -t "$SESSION" status-right-length 60
tmux set-option -t "$SESSION" status-right '#[range=user|rebuild-jvm,bg=colour130,fg=colour231,bold] rebuild JVM #[norange,default] %H:%M '
tmux bind-key -n MouseDown1Status if-shell -F "#{&&:#{==:#{session_name},$SESSION},#{==:#{mouse_status_range},rebuild-jvm}}" \
    "$REBUILD_WINDOW" 'select-window -t ='
tmux bind-key B if-shell -F "#{==:#{session_name},$SESSION}" "$REBUILD_WINDOW"

# Commands go in only once every split is done, so no process starts in a pane
# that is still being resized.
for i in "${!P_ID[@]}"; do
    tmux send-keys -t "${P_ID[i]}" "${P_CMD[i]}" C-m
done

trap - ERR

tmux select-window -t "$SESSION:${W_NAME[0]}"
tmux select-pane -t "${P_ID[0]}"

if (( ATTACH )); then
    tmux attach -t "$SESSION"
else
    step "Session '$SESSION' is running - attach with: tmux attach -t $SESSION"
fi
