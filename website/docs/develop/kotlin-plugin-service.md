# Kotlin plugin services

A plugin service does not have to be written in TypeScript. When a project needs functions that
wrap a JVM library, a solver, or state that has to live outside the platform, it can ship them as
a **Kotlin plugin service**: its own process, registered like any other plugin, that contributes
functions to the script language and answers calls to them.

The platform provides two modules for this, the Kotlin counterpart of `@mdeo/service-common`:

| Module | What it gives you |
| --- | --- |
| `plugin-service` | The manifest at `GET /`, the [session](/develop/sessions) endpoint with token verification, version negotiation and keepalives, and configuration from the environment |
| `script-plugin-service` | `scriptContribution`, which declares script functions and implements them in one place, and the service that answers the [`script-functions` protocol](/develop/script-functions-protocol) |

::: info Contributions only
A Kotlin plugin service provides **contribution plugins**. A language needs a Langium frontend,
which only exists in TypeScript, so a plugin that adds a language is written with
`@mdeo/service-common`.
:::

## Depending on the modules

The modules live in the `platform` Gradle build and are not published to a repository. Include
that build in yours, and Gradle substitutes the dependency with the module built from source:

```kotlin
// settings.gradle.kts
includeBuild("../mdeo-cloud/platform")
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.10"
    application
}

dependencies {
    implementation("com.mdeo:script-plugin-service:1.0.0-SNAPSHOT")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.example.routing.MainKt") }
```

`script-plugin-service` brings `plugin-service`, the protocol, and the type references of
`com.mdeo.expression.ast.types` with it.

## A complete service

```kotlin
package com.example.routing

import com.mdeo.expression.ast.types.BuiltinTypes
import com.mdeo.expression.ast.types.genericClassType
import com.mdeo.pluginservice.PluginDefinition
import com.mdeo.pluginservice.icon
import com.mdeo.pluginservice.runPluginService
import com.mdeo.scriptfunctions.service.scriptContribution

fun main() {
    val listOfString = genericClassType("builtin", "List", typeArgs = mapOf("T" to BuiltinTypes.STRING))

    val routing = scriptContribution("routing") {
        description = "Route planning"

        function("shortestTour") {
            parameter("stops", listOfString)
            implementation { call ->
                val stops = call.argument<MutableList<String>>(0)
                val tour = solve(stops)
                stops.clear()
                stops.addAll(tour)
                null
            }
        }

        function("tourLength") {
            parameter("stops", genericClassType("builtin", "ReadonlyList", typeArgs = mapOf("T" to BuiltinTypes.STRING)))
            returns(BuiltinTypes.DOUBLE)
            implementation { call -> length(call.argument<List<String>>(0)) }
        }
    }

    runPluginService(
        PluginDefinition(
            id = "routing-service",
            name = "Routing",
            description = "Route planning functions for scripts",
            icon = icon("path" to mapOf("d" to "M3 12h18")),
            contributions = listOf(routing)
        )
    )
}
```

A script in a project that has the plugin enabled now calls `shortestTour(stops)` like any other
function. `runPluginService` blocks until the process is stopped.

## Declaring functions

`scriptContribution(id) { … }` declares a contribution to the `script` language. Its id is what
calls address, as `contrib:<id>`, and must be unique within a project.

| In `scriptContribution` | Meaning |
| --- | --- |
| `description` | Shown in the plugin details view |
| `sessionName` | The name of the `script-functions` session, `functions` by default |
| `sessionDescription` | What the session is for, shown in the plugin details view |
| `function(name, overload = "") { … }` | One signature of one function. Declare the same name again with another `overload` key to add an overload |

| In `function` | Meaning |
| --- | --- |
| `parameter(name, type)` | The next parameter |
| `returns(type)` | The return type; `void` unless declared |
| `generics("T", …)` | Generic type parameters, referenced with `GenericTypeRef("T")` |
| `isVarArgs` | Whether the last parameter takes any number of arguments |
| `operation` | The operation name on the wire; the function name, or `name/overload` for a named overload |
| `implementation { call -> … }` | What answers a call |

Types are the references from `com.mdeo.expression.ast.types`: `BuiltinTypes` for scalars,
`genericClassType("builtin", "List", typeArgs = …)` for collections.

From that one declaration the service builds both halves: the external implementations in the
[contribution payload](/develop/script-contributions#implementations-outside-the-platform), and the
operation table of the session answering them. A declared function cannot lack an operation, and
an operation cannot answer a function nobody declared.

Mistakes surface when the service starts, not when a project loads the plugin: a function without
an implementation, a signature declared twice, and a lambda anywhere in a signature are all
rejected by `scriptContribution`. A lambda is code inside the execution and cannot be sent.

## Writing an operation

An operation receives the call and returns the result:

```kotlin
implementation { call ->
    val values = call.argument<MutableList<Double>>(0)
    values.replaceAll { it / values.sum() }
    values.size
}
```

Arguments arrive as plain Kotlin values:

| Script type | Kotlin value |
| --- | --- |
| `int`, `long`, `float`, `double` | `Int`, `Long`, `Float`, `Double` |
| `boolean`, `string` | `Boolean`, `String` |
| `null` | `null` |
| `List`, `Bag` | `MutableList` |
| `Set`, `OrderedSet` | `MutableSet`, iterating in insertion order |
| `Map` | `MutableMap`, iterating in insertion order |

Return any of these, or `null` for a void function. A returned collection can be a new one or one
of the arguments — returning an argument returns that very collection to the script — and it
becomes the collection type the signature declares.

The rules of the round trip:

- **Change only what the signature lets you change.** A parameter declared as a mutable collection
  (`List`, `Set`, `Bag`, `OrderedSet`, `Map`) may be changed. Everything else — `ReadonlyList` and
  the other readonly types, `Any`, scalars — may not, and changing it fails the call.
- **Only what changed goes back.** After the operation returns, every collection it was given is
  compared with what it received, and only the differences are sent.
- **A call takes effect completely or not at all.** An operation that throws fails the call, the
  script sees the exception's message, and none of the changes is applied.
- **Identity is kept.** The same collection passed twice is the same object twice, and a collection
  that contains itself does so here as well.

Calls on one session are answered one at a time, in the order they arrive, and operations run on
`Dispatchers.Default`. Wrap blocking I/O in `withContext(Dispatchers.IO)`. `call.session` says
which project and execution the call belongs to.

Each connection starts with nothing: collections are never shared between two executions.

## Configuration

`runPluginService` reads the same environment variables as the TypeScript services:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `3000` | Port to listen on |
| `HOST` | `0.0.0.0` | Host to bind to |
| `BACKEND_API_URL` | `http://localhost:8080/api` | The backend API. Session tokens are verified against the keys it publishes at `/.well-known/jwks.json` |
| `JWT_ISSUER` | `mdeo-platform` | The issuer every accepted token must name |

Pass a `PluginServiceConfig` instead to set them in code.

## Adding routes of your own

`runPluginService` starts a server that serves nothing but the plugin. To add routes, health checks
or other Ktor plugins, install the plugin service into an application you configure yourself:

```kotlin
embeddedServer(Netty, port = config.port, host = config.host) {
    pluginService(definition, JwksSessionTokenVerifier(config.backendApiUrl, config.jwtIssuer))
    routing {
        get("/health") { call.respondText("ok") }
    }
}.start(wait = true)
```

## Deployment

A Kotlin plugin service is deployed like any other plugin: run it where the backend and the
execution services can reach it, then register its URL as a plugin. The backend reads the manifest
from `GET /`, and executions dial its sessions directly, so the execution services must be able to
reach the same URL. A session is a WebSocket upgrade on the service's own port; see
[Sessions — Deployment](/develop/sessions#deployment) for what a proxy in front of it needs.

## Testing

`ScriptFunctionsServiceSession` answers protocol messages without any connection, which is the
quickest way to test operations:

```kotlin
val service = ScriptFunctionsServiceSession(routing.operations)
val answer = runBlocking {
    service.handle(ClientMessage.Call(1, "tourLength", objects, args))
}
```

To test the whole service, `pluginService` runs inside Ktor's `testApplication`. Pass a
`SessionTokenVerifier` that accepts a fixed token instead of verifying against a backend.

## See also

- [Script contributions](/develop/script-contributions) — what a contributed function is
- [The `script-functions` protocol](/develop/script-functions-protocol) — the wire format these modules speak
- [Sessions](/develop/sessions) — addressing, tokens and liveness
