# Language service HTTP API

Every plugin service exposes the same endpoints, implemented by `startLanguageService` in
`@mdeo/service-common`. A plugin author normally never touches them — this page documents the contract
for debugging, and for anyone implementing a plugin service in another stack.

All endpoints except `GET /` and the static assets require a JWT issued by the backend, presented as
`Authorization: Bearer <token>`, and are checked against a scope.

A caller can say how long it is willing to wait by sending `X-Mdeo-Timeout-Ms` with the
milliseconds it has left. The backend sends it with every file data computation and plugin request,
never more than its own configured maximum. A handler sees the limit as `context.signal`, which
aborts when the time is up or the caller disconnects; every `serverApi` call made while handling the
request is aborted with it and passes the remaining time on to the backend. A handler that fails
after its deadline passed is answered with `504`.

Responses larger than 1 KiB are **compressed** with gzip or deflate when the caller sends
`Accept-Encoding`, and a request body sent with `Content-Encoding: gzip` or `deflate` is accepted.
The backend, the execution services and the workbench proxy compress the same way, and WebSocket
connections negotiate `permessage-deflate`. A service implemented in another stack should do the
same: the platform's payloads are JSON that shrinks by an order of magnitude.

## `GET /`

Returns the [plugin manifest](/develop/manifest). This is the only endpoint the backend needs to
register a plugin.

Static asset paths in the response are rewritten to `static/` or `static/<SERVICE_VERSION>/`.

```bash
curl http://localhost:3000/ | jq
```

## `GET /static/*`

The built ES modules and stylesheets referenced by the manifest — `language.js`, `editor.js`,
`styles.css`, and any workers. Served with CORS enabled and with the COOP/COEP headers the workbench
requires.

## `POST /data/:languageId/:key`

Computes [file data](/guide/concepts#file-data). Called by the backend when a cached entry is missing
or has been invalidated.

**Scope:** `file-data:read`

```json
{
  "project": "0a2f…",
  "source": {
    "path": "/models/plan.m",
    "content": "using \"./tasks.mm\"\n…",
    "version": 7
  },
  "contributionPlugins": []
}
```

`source` is omitted for directory-level computations. `contributionPlugins` carries the payloads of
the contribution plugins active for the project, and is part of the key under which the service pools
its Langium instances.

**Contribution hashes.** Every request also carries `contributionHash`, which identifies the
contribution set. A service that answers with the header `X-Mdeo-Contribution-Hashes` tells the
backend it keeps sets by hash; from then on the backend sends `contributionHash` without
`contributionPlugins`. A service that does not hold the set a hash stands for — after a restart, or
when the set changed — answers `409` with `X-Mdeo-Contributions-Unknown`, and the backend sends the
request again with the payloads. This applies to `/data`, `/request` and `/executions` alike, and
`@mdeo/service-common` does all of it; a service in another stack can ignore it and always receives
the payloads.

```json
{
  "data": { },
  "fileDependencies": [{ "path": "/models/tasks.mm", "version": 3 }],
  "dataDependencies": [{ "path": "/models/tasks.mm", "key": "ast" }]
}
```

The dependency lists are what let the backend invalidate correctly. Anything a handler reads through
`context.serverApi` is tracked automatically and merged into whatever the handler returns.

Responses: `404` for an unknown language or an unregistered data key, `403` for a missing scope.

## `POST /request/:languageId/:key`

An arbitrary language-specific request. Registered only if the service has `requestHandlers`.

**Scope:** `file-data:read`

```json
{
  "project": "0a2f…",
  "body": { },
  "contributionPlugins": []
}
```

The response is `{ "data": … }`, or `{ "data": null }` when the handler produced nothing.

This is the channel the config language uses to talk to its contribution plugins: `config` to compute
section data, and the `config-execution-*` keys to manage runs.

## `POST /:languageId/executions`

Starts an execution. Registered only if the service has `executionHandlers`.

```json
{
  "executionId": "b71c…",
  "project": "0a2f…",
  "filePath": "/optimize.config",
  "fileContent": "problem {\n…",
  "fileVersion": 12,
  "data": { },
  "contributionPlugins": []
}
```

The `executionId` is created by the backend beforehand, so progress can be attributed to it from the
first moment. `data` is the payload the language's action handler produced when the user triggered the
action.

## Execution follow-ups

| Endpoint | Purpose |
| --- | --- |
| `GET /:languageId/executions/:executionId/summary` | Markdown summary of the run |
| `GET /:languageId/executions/:executionId/files` | The result file tree |
| `GET /:languageId/executions/:executionId/files/*` | One result file |
| `POST /:languageId/executions/:executionId/cancel` | Cancel a running execution |

## WebSocket

Two WebSocket endpoints are attached to the same HTTP server, dispatched by path:

| Path | Purpose |
| --- | --- |
| `/ws/executions` | Execution results and progress, read by the backend on a connection it keeps between requests |
| `/ws/sessions/:kind/:targetId/:name` | A [session](/develop/sessions): a long-lived binary connection between one execution and one plugin target |

The execution endpoint authorizes each request on the connection separately, because one
connection is shared by unrelated callers. A session authorizes once, at the handshake, because
one connection *is* one conversation.

## Configuration

`parseServiceConfigFromEnv()` reads:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `3000` | Listening port |
| `HOST` | `0.0.0.0` | Bind address |
| `BACKEND_API_URL` | `http://localhost:8080/api` | Backend base URL for the `ServerApi` |
| `JWT_ISSUER` | `mdeo-platform` | Expected issuer of incoming tokens |
| `MAX_LANGIUM_INSTANCES` | `5` | Size of the Langium instance pool |
| `MAX_SESSION_INSTANCES` | `2` | How many pool instances open sessions may hold at once |
| `LANGIUM_ACQUIRE_TIMEOUT_MS` | `30000` | How long a request waits for a free instance before failing |
| `MAX_REQUEST_BODY_BYTES` | 64 MiB | Upper bound on a request body; file contents travel in the body |
| `SERVICE_VERSION` | — | When set, static assets are served under `/static/<version>/` |

Language-specific variables are read by the plugin itself — for example
`SCRIPT_EXECUTION_SERVICE_URL` and `MODEL_TRANSFORMATION_EXECUTION_SERVICE_URL`.

## The Langium instance pool

Each language keeps a pool of Langium instances keyed by the set of active contribution plugins,
because two projects with different plugin sets need different grammars. Instances are reused when the
key matches, evicted least-recently-used when the pool is full, and reset after every request so that
no document survives into the next one.

This is why `MAX_LANGIUM_INSTANCES` is a memory-versus-concurrency trade-off rather than a
straightforward throughput setting.

A [session](/develop/sessions) on a language target takes its instance *out* of the pool for the
lifetime of the connection, so it is governed by its own budget rather than by this one. A
request that finds every instance held now fails after `LANGIUM_ACQUIRE_TIMEOUT_MS` instead of
waiting indefinitely.
