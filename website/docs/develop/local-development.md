# Local development

Three ways to run the platform while working on it: everything in Docker, the frontend and plugin
services on the host with the backend in Docker, or everything on the host in one tmux session with
[`tools/run-dev.sh`](#everything-on-the-host-with-tools-run-dev-sh).

## Everything in Docker

```bash
docker compose -f infra/docker-compose-dev.yaml up --build
```

Builds every image from the checkout and exposes the internal ports:

| Port | Service |
| --- | --- |
| 4242 | Workbench |
| 8080 | Backend API |
| 3000 | `service-metamodel` |
| 3001 | `service-model` |
| 3002 | `service-script` |
| 3003 | `service-model-transformation` |
| 3004 | `service-config` |
| 3005 | `service-config-optimization` |
| 3006 | `service-config-mdeo` |
| 3007 | `service-model-csv` |
| 3008 | `service-csv` |
| 5432–5435 | PostgreSQL (backend, script, model-transformation, optimizer) |

These are the same ports the workbench's Vite proxy expects, so the two setups below line up: you
can leave the plugin services running in Docker and start only `npm run dev` on the host, and the
dev server reaches the containers without further configuration. Running a host copy of a service
that is also up in Docker fails on the port instead of quietly shadowing it.

Three `optimizer-execution` nodes are started and wired as peers, so distributed search can be
exercised locally.

Slow to iterate on, but the closest thing to production.

## Frontend and plugins on the host

Faster for language and editor work. Start the backend, the databases and the execution services in
Docker, and run the rest with npm.

```bash
cd app
npm install
npm run build          # packages, editor CSS, workbench
```

Then, in parallel:

```bash
# TypeScript project references, in watch mode
npm run watch

# the workbench dev server on http://localhost:4242
npm run dev

# one per plugin service
npm run -w @mdeo/service-metamodel watch
npm run -w @mdeo/service-metamodel watch:static
```

The workbench's Vite config already proxies the plugin services, so the same-origin plugin URLs work
without further configuration:

| Path | Target |
| --- | --- |
| `/plugin/metamodel` | `http://localhost:3000` |
| `/plugin/model` | `http://localhost:3001` |
| `/plugin/script` | `http://localhost:3002` |
| `/plugin/model-transformation` | `http://localhost:3003` |
| `/plugin/config` | `http://localhost:3004` |
| `/plugin/config-optimization` | `http://localhost:3005` |
| `/plugin/config-mdeo` | `http://localhost:3006` |
| `/plugin/model-csv` | `http://localhost:3007` |
| `/plugin/csv` | `http://localhost:3008` |
| `/api` | `http://localhost:8080` |

Set `PORT` accordingly when starting each service — or start that service from
`infra/docker-compose-dev.yaml` instead, which publishes it on the same port. Adding a new plugin
means adding a proxy entry — see the end of [Add a plugin](/develop/add-a-plugin).

Proxy paths are matched by prefix in declaration order, so a longer path has to be declared before
any shorter path it starts with: `/plugin/model-transformation` and `/plugin/model-csv` before
`/plugin/model`, `/plugin/config-optimization` and `/plugin/config-mdeo` before `/plugin/config`.

The proxy also injects the `Cross-Origin-Opener-Policy` and `Cross-Origin-Embedder-Policy` headers the
workbench needs, which is why plugin services should be reached through it rather than directly.

## Everything on the host with `tools/run-dev.sh`

Starts the whole stack in one tmux session: the databases in Docker, every watcher and plugin
service, the backend, both execution services and three federated optimizer nodes. Needs Docker,
Node, a JDK and tmux 3.2 or newer.

```bash
tools/run-dev.sh
```

Before any pane is created, the script runs these steps in order and stops at the first failure:

1. Stops a previous dev session.
2. Stops the containers from `infra/docker-compose-dev.yaml` except the databases (they are stopped,
   not removed). With `restart: unless-stopped`, a stack once started with `up --build` comes back
   on every boot and holds the ports the host services need.
3. Checks that ports 4242, 3000–3008 and 8080–8085 are free.
4. Starts the four PostgreSQL containers.
5. Runs `npm ci` in `app/`.
6. Builds the TypeScript packages and all JVM services once, with a single Gradle build.

| Option | Effect |
| --- | --- |
| `--no-install` | Skip `npm ci` |
| `--no-build` | Skip the TypeScript and Gradle builds |
| `--fresh` | Stop the Gradle daemons and delete `platform/.gradle` first. Use this when Gradle reports empty jars or unresolved references on a clean tree |
| `--no-attach` | Create the session without attaching to it |

Set `MDEO_JAVA_OPTS` (for example `-Xmx1g`) to pass extra options to every JVM service.

The session is called `mdeo-dev`. It has a `core` window with `npm run watch`, the workbench dev
server and the backend, plus one window per plugin with its watchers, its service and its execution
services: `script-execution` under `script`, `model-transformation-execution` under
`model-transformation`, and the three optimizer nodes under `config-mdeo`. The status bar is at the
top; switch windows by clicking them or with `Ctrl-b n` / `Ctrl-b p`.

The TypeScript panes pick up changes on their own. Gradle has no watch mode, and running several
Gradle builds at once makes them conflict over the shared modules, so the JVM services are only
rebuilt on request. Click **rebuild JVM** in the status bar, press `Ctrl-b B`, or run
`tools/rebuild-jvm.sh`. This stops all JVM services, runs one Gradle build and starts them again. If
the build fails, the services stay stopped and the rebuild window keeps the error open.

`tools/stop-dev.sh` closes the session. `tools/stop-dev.sh --all` also stops the Docker containers
and the Gradle daemon.

## Which watcher does what

| Command | Rebuilds |
| --- | --- |
| `npm run watch` (root) | All packages, through TypeScript project references |
| `npm run -w @mdeo/service-x watch` | Restarts the service process on change |
| `npm run -w @mdeo/service-x watch:static` | The served ES modules in `static/` |
| `npm run -w @mdeo/editor-x watch:css` | The editor stylesheet |

Changes to a served module require a page reload, because the workbench imports it once per session.

## Backend and execution services

The Kotlin side is a Gradle build:

```bash
cd platform
./gradlew build
./gradlew :backend:run
```

Modules: `backend`, `common`, `expression`, `metamodel`, `model-transformation`, `script`,
`optimizer`, and the execution services `script-execution`, `model-transformation-execution`,
`optimizer-execution` with their shared `execution-common`.

## Linting and formatting

```bash
cd app
npm run format          # prettier
npm run lint            # eslint --fix
npm run format:check    # check only, as CI does
npm run lint:check
```

## The documentation site

```bash
cd website
npm install
npm run validate        # parse and validate every DSL sample
npm run dev             # http://localhost:5173/mdeo-cloud/
npm run build           # validates, then builds the static site
```

`npm run validate` loads the built language packages from `app/packages/*/dist` and parses every file
under `website/samples` in one shared Langium environment, exactly as the workbench does. It reports
parser errors, lexer errors and validation diagnostics, and fails the build on any error — so a sample
in the docs cannot drift away from the languages it documents.

Run `npm run build:packages` in `app/` first if the language packages have changed.
