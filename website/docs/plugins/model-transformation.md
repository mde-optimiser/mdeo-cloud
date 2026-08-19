# Model Transformation plugin

Rewrites models. A transformation matches a fragment of a model and changes it; during an
optimisation, transformations are the mutation operators — the only moves the search may make.

## At a glance

| | |
| --- | --- |
| **Plugin id** | `model-transformation-service` |
| **Display name** | Model Transformation |
| **Description** | Language support for model transformation definitions (`.mt` files) |
| **Default URL** | `/plugin/model-transformation` |
| **Source** | `app/packages/service-model-transformation`, `app/packages/language-model-transformation`, `app/packages/editor-model-transformation` |
| **Depends on** | The [Metamodel plugin](/plugins/metamodel); executions go to `model-transformation-execution` |

## Languages contributed

| Language id | Name | Extension | Textual editor | Graphical editor | Generated |
| --- | --- | --- | --- | --- | --- |
| `model-transformation` | Model Transformation | `.mt` | ✅ | ✅ | ❌ |
| `model-transformation_gen` | Generated Model Transformation | `.mt_gen` | ❌ | ✅ | ✅ |

### The model transformation language

A `.mt` file names its metamodel and then contains a sequence of statements.

<<< @/../samples/language-tour/match.mt{mt}

#### Patterns

A `match { ... }` block is a pattern. Every element of the pattern has to be found in the model before
any of the marked changes are applied. Elements can be:

| Element | Syntax | Meaning |
| --- | --- | --- |
| Object | `name: Class { ... }` | An object of that class must exist |
| Link | `source[.property] -- target[.property]` | A link must exist between two matched objects |
| Reference | `name { ... }` | Constrain or update an object matched in an *enclosing* scope |
| Delete | `delete name` | Remove an object matched earlier |
| Variable | `var name[: type] = expression` | Bind a value for later use in the pattern |
| Condition | `where expression` | An arbitrary boolean condition on the match, or — inside a block — on that condition |
| Application condition | `forbid [name] { ... }` / `require [name] { ... }` | A sub-pattern that must not / must be findable |

Objects and links can carry a modifier:

| Modifier | Effect |
| --- | --- |
| *(none)* | The element must exist and is left untouched |
| `create` | The element is added |
| `delete` | The element is removed |

#### Application conditions

A `forbid` block rejects the match as soon as **its whole sub-pattern** can be found; a `require`
block demands that its whole sub-pattern is found. Each block is a graph of its own, matched
independently of the pattern around it and of every other block:

<<< @/../samples/language-tour/application-conditions.mt{mt}

The grouping is what carries the meaning. Two blocks reject the match when *either* of them matches;
the same elements inside one block reject it only when they *all* match together:

```mt
// rejected when the patient is admitted OR when a better candidate exists
forbid { a: Admission {}   a.patient -- patient }
forbid { better: Patient { surgeryDuration < duration } }

// rejected only when the patient is admitted AND a better candidate exists
forbid {
    a: Admission {}   a.patient -- patient
    better: Patient { surgeryDuration < duration }
}
```

A block may name itself — `forbid alreadyAdmitted { ... }` — following Henshin's nested names. The
name identifies the block in diagnostics, and in the graphical editor its elements are tagged with
`«forbid alreadyAdmitted»`. Inside a block you may:

- declare objects and links that belong to the condition graph alone,
- refer to objects of the enclosing pattern, which anchors the condition to the match, and
- constrain an object of the enclosing pattern through a reference — `patient { age > 60 }`.

Objects declared inside a block are not bound by the match: they are invisible outside their block,
and outside it they cannot be used in expressions, created or deleted. A condition decides whether a
match is admissible and never writes to the model, so its objects only compare properties — `=` is
rejected inside a block.

A block may also carry `where` clauses. A clause inside a block constrains that block — the block
only holds when its graph is found *and* its clauses are satisfied — and it may compare the block's
own objects with each other and with everything the match binds:

```mt
forbid betterCandidate {
    better: Patient { isMandatory == false }
    where better.surgeryDuration < patientDuration
}
```

Since the clause belongs to the block, it is subject to the same grouping rule as everything else in
it: the clause above only ever prevents *this* block from holding. Several clauses in one block are
a conjunction, and a block that holds nothing but a clause is a plain guard on the match.

::: tip Moving elements between blocks
In the graphical editor, an element created in *forbid* or *require* mode starts in a block of its
own. Use **Move to Block** in the context rail to move it — together with everything connected to it
— into another block, into a new one, or back into the match pattern. Where clauses move the same
way, unless they read an object of their block: such a clause means nothing outside it, so the move
is not offered. **Add Where Clause** on a member of a block adds a clause to that block.
:::

Inside an object's braces, `=` assigns a property while `==`, `!=`, `<`, `>`, `<=` and `>=` constrain
the match:

```mt
rectangle: Rectangle {
    visible == true
}
```

A property may be assigned at most once per object, since a second `=` on the same property would
silently overwrite the first. Comparisons are not restricted: the same property may be constrained
several times — `width > 10` together with `width < 100` — and a property that is compared may still
be assigned in the same object.

::: tip Names are file-global
Object names must be unique across the whole `.mt` file, not just within one pattern. An object
matched in an outer scope is referred to by name — `rectangle { visible = true }` — rather than
matched again.
:::

#### Statements

<<< @/../samples/language-tour/control-flow.mt{mt}

| Statement | Meaning |
| --- | --- |
| `match { … }` | Apply the rewrite once |
| `if match { … } then { … } else { … }` | Apply the second block only if the pattern matches |
| `for match { … } do { … }` | Run the body once per match |
| `while match { … } do { … }` | Repeat while the pattern still matches |
| `until match { … } do { … }` | Repeat until the pattern matches |
| `if (expr) { … } else if (expr) { … } else { … }` | Ordinary conditional on an expression |
| `while (expr) { … }` | Ordinary loop on an expression |
| `stop` | End the transformation successfully |
| `kill` | Abort the transformation and discard the result |

Expressions use the same syntax as the [Script language](/plugins/script) — the two share an
expression and type system.

### The generated model transformation language

`.mt_gen` files are transformations the platform produced itself. An optimisation run can generate
mutation rules from the `create` / `delete` / `mutate` entries of a `search` block, and writes them
into the result tree so you can see exactly which rewrites the search was allowed to perform.

## Contribution plugins contributed

None.

## Server-side capabilities

| File data key | Contents |
| --- | --- |
| `ast` | The serialised AST |
| `typed-ast` | The type-annotated AST the execution service interprets |
| `model-transformation-text` | The textual rendering of a generated `.mt_gen` file |

**Execution.** A transformation can be run against a model. The plugin forwards the request to the
`model-transformation-execution` service, configured through
`MODEL_TRANSFORMATION_EXECUTION_SERVICE_URL`.

## Graphical editor

Transformations have a diagram editor too. Pattern objects and links appear as nodes and edges, with
their modifier reflected in the styling, so the effect of a rule can be read at a glance.
