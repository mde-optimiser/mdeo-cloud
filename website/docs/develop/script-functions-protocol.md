# The `script-functions` protocol

`script-functions` is the protocol a [session](/develop/sessions) speaks when a script calls a
function whose implementation lives in a plugin service. It belongs to the script language, not to
the platform: the platform carries its bytes and reads none of them.

Most services should not implement it by hand.
[Kotlin plugin services](/develop/kotlin-plugin-service) get it from `script-plugin-service`, whose
message classes live in `script-functions-protocol`. This page is the specification those modules
implement, for a service written in anything else and for anyone debugging the traffic.

**Version:** 1. **Session type:** `{ "protocol": "script-functions", "versions": [1] }`.

## Roles

The **client** is the execution running the script. The **service** is the plugin service. The
client sends calls, one at a time, and waits for each answer before it sends the next call. The
service never sends anything that is not an answer.

A call is **copy-restore**. The client copies the arguments to the service, including every
collection they reach. The service may change the collections it was allowed to change, and it
answers with the changes. The client checks the whole answer and applies it, or rejects it and
applies nothing.

## Encoding

Every WebSocket binary frame carries exactly one message, encoded as one [CBOR](https://cbor.io)
data item.

A polymorphic value — a message, a wire value, a delta — is a two-element array: its type name,
then a map of its fields.

```
["call", { "callId": 1, "operation": "op", "objects": [], "args": [] }]
["null", {}]
```

- **Fields** are map entries keyed by their name as a text string. A receiver ignores fields it
  does not know. A field left out takes its default.
- **Lengths:** the reference implementation writes arrays and maps with indefinite length. A
  receiver must accept both definite and indefinite lengths.
- **Numbers:** integer fields are CBOR integers. `double` values are 64-bit floats, `float` values
  32-bit floats.
- **Enumerations** such as `kind` are text strings.

## Values

A `WireValue` is one of:

| Type name | Fields | Carries |
| --- | --- | --- |
| `null` | — | `null` |
| `bool` | `value` | A boolean |
| `int` | `value` | A 32-bit integer |
| `long` | `value` | A 64-bit integer |
| `float` | `value` | A 32-bit float |
| `double` | `value` | A 64-bit float |
| `string` | `value` | A text string |
| `ref` | `id` | A collection on the heap, by id |
| `instance` | `name` | An instance of the call's model, by name. Only valid in a call that names a model, and in its answer |
| `record` | `className`, `fields` | A record the contribution defines, sent whole: `fields` maps every field name to its `WireValue` |
| `handle` | `className`, `id` | A handle to state the service keeps, of an opaque class the contribution defines |

Records are immutable values. Their fields hold scalars, strings, instances, other records, and
readonly collections (as `ref`s into the heap). Handles are ids the service chooses; the same state
must go out under the same id every time.

The type name says what the script sees. A service must send back the number type it received,
because `2` and `2.0` are different values to a script.

## The heap

Collections never travel inline. Each is a `HeapObject` listed once per message, and values refer
to it with `ref`. That keeps aliasing and cycles intact: a list passed twice is one object, and a
list can contain itself.

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | integer | — | The collection's id for the whole session |
| `kind` | `list` \| `set` \| `orderedSet` \| `bag` \| `map` | — | What kind of collection it is |
| `version` | integer | `0` | The client's mutation counter for it |
| `mutable` | boolean | `false` | Whether the service may change it |
| `elements` | `WireValue[]` \| null | `null` | The elements, for every kind but `map` |
| `entries` | `WireValue[]` \| null | `null` | A map's entries, as alternating keys and values |

**Ids.** The client assigns positive ids to collections it sends. The service assigns negative ids
to collections it creates, and never one it currently holds a collection under. Both sides keep
using an id for as long as the connection lasts, so a collection the service created and returned
is later sent back under its negative id.

**Reconnects.** A new connection reaches a service that holds nothing. The client gives every
collection an earlier service created a positive id of its own, sends content in full, and refuses
to pass a handle whose state only the earlier service held. A call whose message went out on a new
connection is sent once more, and whatever the first answer created is released.

**Content may be omitted.** When the service already holds a collection at the version the client
is sending, the client leaves `elements` and `entries` out (both `null`). The service uses what it
holds. A service that does not hold that id answers with the `unknown-object` failure, and the
client sends the call once more, this time with all content.

**Mutability comes from the signature.** A collection is `mutable` when some argument reaches it
through a mutable collection type: `List`, `Set`, `OrderedSet`, `Bag` or `Map`. Everything else is
readonly, including anything reached through `Any` and every map key.

## Client messages

### `call`

| Field | Type | Meaning |
| --- | --- | --- |
| `callId` | integer | Correlates the answer; unique within the session |
| `operation` | string | The operation, as the external implementation declares it |
| `objects` | `HeapObject[]` | Every collection the arguments reach |
| `args` | `WireValue[]` | The arguments, in declaration order |
| `modelId` | integer \| null | The model the call works on, or `null` when it needs none |

A call names a model when its function reads the model (`model: "readonly"` on the external
implementation) or when an argument reaches a model instance. A service that does not hold that
model answers with the `unknown-model` failure.

`objects` may refer to each other, and to themselves, in any order. A service creates every listed
collection first and fills them afterwards.

### `model`

| Field | Type | Meaning |
| --- | --- | --- |
| `modelId` | integer | Identifies the model within the session |
| `model` | `WireModel` | The whole model |

Uploads the model the following calls work on. There is no answer.

A service holds **one model per session**. Receiving a model replaces the previous one and ends
everything that belonged to it: the service forgets every collection and every handle it holds —
they may contain or depend on the old model — and whatever it derived from the old model. The client clears its record
of what the service holds at the same time, so the next call sends every collection in full.

The client uploads a model the first time a call needs it, and again only when the model the script
runs on changes. It compares models by content, so an optimizer evaluating several guidance
functions on one solution uploads that solution's model once, however many times it rebuilds it.

A `WireModel`:

| Field | Type | Meaning |
| --- | --- | --- |
| `metamodelPath` | string | The metamodel the model is an instance of |
| `subtypes` | map of string to string[] | For every class, the classes that are it or inherit from it |
| `instances` | `WireInstance[]` | Every instance |

A `WireInstance`:

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | string | Unique within the model |
| `className` | string | The instance's class |
| `attributes` | map of string to `WireValue[]` | Attribute values. A single-valued attribute has at most one value, an unset one none. Enum values are `string`s naming the entry |
| `references` | map of string to string[] | Names of the referenced instances, by association end |

Models are readonly. No delta can address an instance, because instances are not on the heap. A
collection that contains instances can be changed like any other.

### `release`

| Field | Type | Meaning |
| --- | --- | --- |
| `ids` | integer[] | Collections the client no longer holds |
| `handles` | integer[] | Handles the script no longer holds; default `[]` |

The service may forget them. There is no answer.

## Service messages

### `result`

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `callId` | integer | — | The call being answered |
| `objects` | `HeapObject[]` | `[]` | Collections the service created, under negative ids, with their content |
| `deltas` | `Delta[]` | `[]` | Changes to mutable collections of this call, in the order they apply |
| `value` | `WireValue` | `null` | The return value |

A collection the call sent that did not change produces no delta.

### `failure`

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `callId` | integer | — | The call being answered |
| `message` | string | — | What went wrong; the script sees it |
| `code` | string \| null | `null` | `unknown-object`, `unknown-model`, or `null` for any other failure |

On `unknown-object` or `unknown-model` the service has lost what the client thought it held, as
after a reconnect. The client uploads the model again if the call needs one, and sends the call
once more with every collection in full.

After a failure, the client assumes the service's copies of that call's collections are stale and
sends them in full next time. A service keeps them: another collection it holds may contain one, and
the next call refills it in place.

## Deltas

Each delta names the collection it changes with `id`.

| Type name | Fields | Applies to | Meaning |
| --- | --- | --- | --- |
| `splice` | `index`, `deleteCount`, `insert` | `list`, `orderedSet` | Replace `deleteCount` elements at `index` with `insert` |
| `add` | `values` | `set`, `orderedSet`, `bag` | Add each value; appended to an ordered set, one occurrence each to a bag |
| `remove` | `values` | `set`, `orderedSet`, `bag` | Remove each value; one occurrence each from a bag |
| `count` | `value`, `count` | `bag` | Set how often `value` occurs |
| `put` | `key`, `value` | `map` | Associate `value` with `key` |
| `removeKey` | `key` | `map` | Remove `key` |
| `replace` | `elements` | any | Replace the whole content; for a map, alternating keys and values |

Values in deltas match collection elements by value for scalars and by id for `ref`.

## What the client rejects

The client checks a whole `result` before applying anything. Any of these rejects it, and the
script sees an error with nothing changed:

- a delta against a collection that was not part of this call, or was sent readonly;
- a delta that does not fit the collection's kind;
- a `splice` whose `index` and `deleteCount` fall outside the collection, as the deltas before it
  leave it;
- a negative `count`, or a map `replace` with an odd number of values;
- a created collection with a non-negative id, or with an id already in use;
- a `ref` to a collection that is neither in this call, nor created in this result, nor held from
  an earlier call;
- an `instance` that is not part of the call's model;
- a `record` the contribution does not define, or whose fields are not exactly the declared ones;
- a `handle` of a class the contribution does not define as opaque.

A service should also refuse, with a `failure`, any call whose operation changed a readonly
collection. The client would reject that result anyway, but a failure makes clear whose mistake it
was.

## Example

A call passing a mutable `List<double>` holding `2.0`, and `null`:

```
["call", {
  "callId": 1,
  "operation": "op",
  "objects": [{ "id": 1, "kind": "list", "version": 3, "mutable": true,
                "elements": [["double", { "value": 2.0 }]], "entries": null }],
  "args": [["ref", { "id": 1 }], ["null", {}]],
  "modelId": null
}]
```

The same message as the reference implementation writes it:

```
9f6463616c6cbf6663616c6c496401696f7065726174696f6e626f70676f626a656374739fbf62696401646b696e64646c69
73746776657273696f6e03676d757461626c65f568656c656d656e74739f9f66646f75626c65bf6576616c7565fb40000000
00000000ffffff67656e7472696573f6ffff64617267739f9f63726566bf62696401ffff9f646e756c6cbfffffff676d6f64
656c4964f6ffff
```

A service that removed the element answers:

```
["result", { "callId": 1, "deltas": [["splice", { "id": 1, "index": 0, "deleteCount": 1, "insert": [] }]] }]
```

## Versioning

A change that an existing peer would misread gets a new protocol version. Adding a field with a
default that keeps the old meaning does not, because receivers ignore fields they do not know.

## See also

- [Kotlin plugin services](/develop/kotlin-plugin-service) — the implementation to use
- [Script contributions](/develop/script-contributions#implementations-outside-the-platform) — how a function is declared external
- [Sessions](/develop/sessions) — the connection this runs over
