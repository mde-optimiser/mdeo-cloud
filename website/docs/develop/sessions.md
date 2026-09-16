# Sessions

A **session** is a long-lived binary connection between an execution and a plugin. It exists for
the cases a request cannot serve: a conversation that goes back and forth many times, carries
data that only makes sense in sequence, or has to outlive a single call without paying to
re-establish itself each time.

The platform owns the connection. It does not own what travels on it.

::: info No bundled plugin declares a session yet
Sessions are implemented end to end — declaration, connect endpoint, token, endpoint, pooling and
client. Their first protocol is the script language's `script-functions`, which answers
[external function implementations](/develop/script-contributions#implementations-outside-the-platform).
:::

## What the platform owns, and what it does not

| The platform owns | The plugin owns |
| --- | --- |
| The address a session is reached at | The messages that travel on it |
| The token that authorizes it, and its lifetime | Their encoding |
| Version negotiation at the handshake | Correlation between a question and its answer |
| Keepalives, and deciding a connection is dead | What an error looks like |
| Taking a Langium instance out of the pool and putting it back | What the session is *for* |

There is deliberately no envelope inside a session. Nothing the platform writes wraps what you
send, nothing it reads interprets it, and there is no platform-defined error message. If two
conversations are not the same conversation, declare **two session types** rather than one type
with a prefixed vocabulary — the name is free, and a second type costs nothing.

## Addressing a target

Sessions are declared on things that already have ids: **language plugins** and **server
contribution plugins**. Both id spaces are flat and they overlap — `config-mdeo` is a language id
*and* a contribution id — so every address spells out which it means:

```
lang:script
contrib:script-functions
```

That same string is used everywhere one is written down: the URL segments of both endpoints, the
`target` claim of a session token, the keys of `ServiceConfig.sessions`, log lines, error
messages, and the plugin details view. `@mdeo/plugin` exports `formatPluginTarget` and
`parsePluginTarget`; `com.mdeo.common.model.PluginTarget` is their Kotlin twin.

Contribution ids are unique **within a project**. Adding a plugin whose contribution id is already
taken is refused with `PluginContributionIdConflict`, because an ambiguous address would otherwise
be resolved by whichever row came back first.

## Declaring a session

A session type is declared in the plugin manifest, beside whatever declares the target.

```ts
// On a language plugin
const scriptLanguagePlugin: LanguagePlugin = {
    id: "script",
    // …
    sessions: {
        functions: {
            protocol: "script-functions",
            versions: [1],
            description: "Calls contributed functions implemented outside the platform"
        }
    }
};

// On a server contribution plugin
const contribution: ScriptContributionPlugin = {
    id: "script-functions",
    // …
    sessions: {
        calls: { protocol: "script-functions", versions: [2, 1] }
    }
};
```

| Field | Meaning |
| --- | --- |
| `protocol` | Name of the protocol, owned by whoever defines the contract — `script-functions` belongs to the script language, not to the platform |
| `versions` | Versions this side can speak, most preferred first |
| `description` | What the session is for; shown in the plugin details view |

The manifest is the only place a session type is written down. The connect endpoint hands a
caller the protocol and versions from there, and the session endpoint negotiates against the same
values, so the two cannot drift.

## Serving a session

::: tip Writing the service in Kotlin
A [Kotlin plugin service](/develop/kotlin-plugin-service) serves sessions with `:plugin-service`:
a `SessionHandler` with the same `open`/`onMessage`/`onClose` shape, registered on its contribution.
For `script-functions`, `scriptContribution` registers the handler for you.
:::

In a TypeScript service, register a handler under the target address and the session name:

```ts
const config: ServiceConfig<ScriptServices> = {
    // …
    sessions: {
        "contrib:script-functions": {
            calls: {
                open(ctx) {
                    return {
                        onMessage(data) {
                            ctx.send(answer(data, ctx.version));
                        },
                        onClose(reason) {
                            releaseWhateverWasHeld(reason);
                        }
                    };
                }
            }
        }
    }
};
```

`open` is called once, when the connection has been authorized and a version agreed. What it
returns handles that one connection until it ends.

### The session context

| Field | Meaning |
| --- | --- |
| `projectId`, `executionId` | Who the session belongs to. A session never outlives its execution |
| `target`, `sessionName` | What was addressed |
| `version` | The version both sides agreed on |
| `jwt` | The token the session was opened with |
| `serverApi` | Backend access, already pointed at the project |
| `instance` | For a `lang:` target, the Langium instance this session owns, with the project's contribution plugins loaded |
| `send(data)` | Writes one message |
| `close(reason)` | Ends the session |

A handler registered without a matching declaration — or a declaration without a handler — is
reported as a warning at service start. Both are wiring mistakes that would otherwise only show
up when an execution tries to connect.

## Opening a session

An execution node never dials a plugin directly. It asks the backend, with the token it holds for
the run:

```
POST /api/projects/{projectId}/sessions/{kind}/{targetId}/{name}/connect
```

**Scope:** `execution:read`, on a token bound to an execution.

```json
{
  "url": "ws://script-service/ws/sessions/contrib/script-functions/calls",
  "protocol": "script-functions",
  "versions": [2, 1],
  "token": "eyJ…",
  "expiresAt": 1789000000
}
```

The backend decides whether the project has that target at all, so a run cannot reach a plugin
the project has not enabled. The token it returns carries the single `session:connect` scope and
names the one target and session it may be spent on; it is bound to the execution, so it stops
being accepted the moment the run ends.

From Kotlin, `SessionResolver` makes that call and caches the answer for the run, refetching the
token as it nears its expiry. `SessionClient` dials the endpoint, keeps it alive, and reconnects
when it drops — a reconnect restores the *transport*, not the conversation, so
`onReconnect` is where a protocol re-establishes whatever state the new connection starts without.

## The session endpoint

```
GET /ws/sessions/:kind/:targetId/:name        (WebSocket upgrade)
```

The token goes in `Authorization: Bearer`, or in a `token` query parameter for callers that
cannot set headers. A service implementing this endpoint itself verifies the token as an RS256 JWT
against the keys at `<BACKEND_API_URL>/.well-known/jwks.json`, requires the configured issuer, and
accepts it only when its `scope` contains `session:connect`, its `target` and `session` claims name
exactly the addressed target and session, and it carries `projectId` and `executionId`. The caller lists the versions it can speak in `?v=2,1`, most preferred first,
and the first one the plugin also declares wins.

A connection that never becomes a session is closed with a code and a reason:

| Code | Meaning |
| --- | --- |
| `4401` | Token missing, invalid, or issued for a different target or session |
| `4404` | This service serves no such target or session |
| `4409` | No version both sides can speak |
| `4503` | No capacity to hold another session |
| `4408` | The peer stopped answering keepalives (TypeScript services; Kotlin services close an unresponsive connection through Ktor's own timeout) |

Everything a plugin itself closes a session with is its own business.

## Lifetime and liveness

Sessions are **exempt from request timeouts**. A call can legitimately take as long as the work
takes, so there is no deadline on a message — liveness comes from keepalives instead. Both sides
ping every 30 seconds and give up on a peer that has not answered for 90.

This is not optional. The reverse proxy in front of a plugin service drops connections that have
been idle for an hour, and a session doing long stretches of local work would otherwise be
collected mid-conversation.

## Sessions and the Langium pool

A `lang:` session **takes its Langium instance out of the pool** for the lifetime of the
connection and resets it back in when the session closes. That is the only way to hold parser and
scope state across messages, but it means the instance is unavailable to the request path for as
long as the run lasts.

Two settings keep that from starving the service:

| Variable | Default | Meaning |
| --- | --- | --- |
| `MAX_SESSION_INSTANCES` | `2` | How many instances open sessions may hold at once |
| `LANGIUM_ACQUIRE_TIMEOUT_MS` | `30000` | How long an ordinary request waits for a free instance before failing |

The session budget is **refused** when exhausted rather than queued: a caller waiting for a slot
held by a session that has not finished would be waiting for the rest of a run. The request path's
wait, by contrast, is bounded — without a bound, one instance that is never released stalls every
request behind it with nothing to show for it.

The instance is picked exactly like one for a request: it has the contribution plugins the
project registers for that language loaded. Requests carry that list in their body because the
backend relays them, but a session is dialed directly. So while it opens the connection, the
language service fetches the list itself, with the session token:

```
GET /api/projects/{projectId}/sessions/lang/{languageId}/{name}/contribution-plugins
```

**Scope:** `session:connect`, on the token issued for exactly that session. The caller never sends
the list, so it cannot choose what gets loaded. When the fetch fails, the connection is closed
with `4503`.

`contrib:` sessions hold no Langium instance and draw on neither budget.

## Deployment

A session is an ordinary WebSocket upgrade on the plugin service's own port, so it needs what the
execution WebSocket already needs. In the compose deployment each `location ^~ /plugin/…/` block
already forwards upgrades with a 3600-second read timeout. In the Kubernetes deployment the hop is
cluster-internal — an execution node reaches a plugin service directly — so no gateway route is
involved.

## See also

- [Contribution plugins](/develop/contribution-plugins) — what a contribution is
- [Language service HTTP API](/develop/service-api) — the request-shaped endpoints
- [Plugin manifest reference](/develop/manifest) — where a declaration lives
- [Kotlin plugin services](/develop/kotlin-plugin-service) — serving sessions from Kotlin
- [The `script-functions` protocol](/develop/script-functions-protocol) — the first protocol spoken on sessions
