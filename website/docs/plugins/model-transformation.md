# Model Transformation plugin

Rewrites models. A transformation matches a fragment of a model and changes it; during an
optimisation, transformations are the mutation and crossover operators — the only moves the search
may make.

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
| Delete | `delete name` | Remove an object bound by an earlier or enclosing statement |
| Variable | `var name[: type] = expression` | Bind a value for later use in the pattern |
| Condition | `where expression` | An arbitrary boolean condition on the match, or — inside a block — on that condition |
| Application condition | `forbid [name] { ... }` / `require [name] { ... }` | A sub-pattern that must not / must be findable |

Objects and links can carry a modifier:

| Modifier | Effect |
| --- | --- |
| *(none)* | The element must exist and is left untouched |
| `create` | The element is added |
| `delete` | The element is removed |

**Injective matching.** Two objects of a pattern with the same class never match the same model
element, and neither do an object and a *bound* object the pattern references — an element input
(`seed { }`), an object of an enclosing or earlier statement, a call output or a loop variable — when
the bound element is of exactly the object's class. So with `seed { }` for an `AllocationContext`
input, a new object `other: AllocationContext { }` never matches `seed` itself, and no `where other !=
seed` is needed. Objects of different classes may match one element when one class is a subclass of
the other (`unit: AbstractAction { }` may be the element `call: ExternalCallAction { }` matched);
write `where a != b` to exclude that. Bound objects are not compared with each other: two inputs may be
the same element. A bound object that the pattern does not reference — only a `where` clause reads
it — is a value and takes no part. The objects of a `forbid` or `require` block are distinct from the
pattern's objects of the same class, not from bound objects.

To remove an object found in the same pattern, put the modifier on the object itself —
`delete book: Book { }`. `delete name` only names objects of an earlier or enclosing statement; inside the pattern
that declares `name`, it is rejected.

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
own. The **Change Modifier** menu in the context rail lists the *Forbid* and *Require* blocks of the
pattern: choose one to move the element — together with everything connected to it — into another
block or into a new one, and *None*, *Create* or *Delete* to move it back into the match pattern.
Where clauses move the same way, unless they read an object of their block: such a clause means
nothing outside it, so the move is not offered. **Add Where Clause** on a member of a block adds a clause to that block.
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

A value assigned to an `int`, `long`, `float` or `double` property takes the property's type
(truncated toward zero for `int` and `long`), as do the elements of a list assigned to a numeric
list property — on created objects, on matched ones and on references (`seed { count = n / 2 }`),
whose property is that of the bound element's class. Assigning `null` leaves an optional property
unset: `maybe = other.maybe` when `other.maybe` is unset, `maybe = null`, or a ternary whose branch
is `null` removes the value of a matched object and sets nothing on a created one. A list assigned
from an unset list property is empty, and `null` elements of a list add no value.

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
| `call name(arg = expr, …) -> (out: name, …)` | Run another transformation — see [Callable transformations](#callable-transformations) |
| `if call name(…) -> (…) then { … } else { … }` | Run the first block, with the outputs, if the callee succeeds, the second if it fails |
| `for x in collection do { … }` | Run the body once per element of a collection |
| `name = expr`, `name += expr` | Assign a variable or an output, or add to a collection output |

Expressions use the same syntax as the [Script language](/plugins/script) — the two share an
expression and type system, including the number literals (`1.0e300`, `2.5E-3f`, `42L`). The
engine compiles every expression to a Gremlin traversal without lambda steps, so the transformation
language offers the part of the script language's library that compiles that way:

| On | Methods |
| --- | --- |
| collections | `size()`, `isEmpty()`, `notEmpty()`, `sum()`, `first()`, `includes(x)` (an element `==` x), `filter`, `map`, `exists`, `all`, `none`, `one`, `find`, `reject` (with a lambda) |
| lists and ordered sets | also `last()` and `indexOf(x)` (the first position of an element `==` x, or `-1`) |
| numbers | `abs`, `floor`, `ceil`, `round`, `log`, `log10`, `pow`, `mod`, `max`, `min`; on `int` and `long` also `a.to(b)`: the `int`s from `a` to `b`, both included, counting down when `b < a` |

They behave as in scripts, in patterns, `where` clauses and statements alike, and several lambdas
may appear in one expression (`a.exists((x) => x == o) && !b.exists((y) => y == o)`). There is no
`random()`: Gremlin has no step for it.

#### Callable transformations

Every `.mt` file is a transformation that other transformations can call, in the style of the method
calls of story diagrams: the callee declares typed **inputs** and **outputs** in its header, and the
caller **imports** the file under a name of its own and runs it with `call`.

<<< @/../samples/callable-transformations/generic/move-component.mt{mt}

**The header.** A file starts with its `using` line, then its imports, then its declarations, one
per line, and only then its statements:

| Line | Meaning |
| --- | --- |
| `import "./path/file.mt" as name` | Make another transformation callable as `call name(…)` |
| `in name: Type` / `in name: Type = default` | An input |
| `out name: Type` | An output |

Declarations belong to the header: a declaration after the first statement does not parse. A file
without declarations is callable too, with no arguments. An input is a **model element** — its type
is a class of the metamodel —, a **scalar** — `int`, `long`, `float`, `double`, `string`, `boolean`
or an enum of the metamodel —, a **collection** of either, `List<T>` or `Set<T>`, or another
**model**, `model` or `List<model>` (see [Model inputs](#model-inputs)). An output is an element, a
scalar or a collection.

| Declaration | When the callee starts | Use in the callee |
| --- | --- | --- |
| `in name: Class` | bound to the element the caller passed | a bound node of every pattern: `name { … }`, `name.end -- other`, `delete name`, and in expressions |
| `in name: Class?` | bound to the passed element; when none (or null) is passed, the object `name` of the first pattern is matched freely | the object of the same name in the first pattern — see [Optional element inputs](#optional-element-inputs) |
| `in name: int = 0` | bound to the argument, or to the default when the caller passes none | a value in expressions |
| `in name: List<Class>` / `Set<int>` | the collection the caller passed | in expressions (`name.size()`, `name.exists(…)`) and in `for x in name do { … }` |
| `in name: model` / `List<model>` | the model, or the models, the caller passed | the target of scope blocks `in name { … }` — see [Model inputs](#model-inputs) |
| `out name: Class` / `out name: int` | `null` | assigned with `name = expression`, in a pattern or as a statement |
| `out name: List<Class>` / `Set<string>` | empty | grown with `name += expression`, in a pattern or as a statement |

A callee whose element inputs are already bound repairs locally around them:

<<< @/../samples/callable-transformations/sync/follow-reader.mt{mt}

The caller imports the callee by its path and gives it a name with `as`; that alias is the only name
of the callee in the file, and `call` uses it. The arguments are named after the callee's inputs;
after `->` the caller binds the outputs it needs to names of its own. They are bound exactly as if a
`match` had bound them: an element output is an object that later patterns reference as `m { }`, a
scalar or collection output is a value.

<<< @/../samples/callable-transformations/move-with-stores.mt{mt}

**Semantics.**

- The callee runs on the same model, with its own variables: only its inputs and outputs cross the
  call.
- A callee that **fails** — a `match` that finds nothing — fails the call, and so the caller, just as
  a failing `match` does. During an optimisation the attempt is then discarded like any other failed
  operator.
- `stop` in the callee **returns from the callee**; the caller goes on with the next statement.
  `kill` in the callee aborts the whole transformation.
- A callee that succeeds without matching anything (a `for match` with no match, an `if match` that
  takes no branch) is a success. Outputs it did not assign are `null`, and collection outputs it did
  not add to are empty. The validator therefore requires every non-nullable single output to be
  assigned on every path that succeeds; declare an output `T?` when that cannot be guaranteed. A
  nullable output can be compared with `null`, but it cannot be a node of a pattern.
- The engine does not undo the changes of a failed callee. Inside an optimisation that does not
  matter, since the whole attempt is discarded.

**Collections.** A `List` keeps its elements in the order they were added, a `Set` keeps one of
equal elements (the same model element, or equal values). `name += value` adds a value to a
collection output — or all elements of a collection value. Inside a pattern it is computed with the
match, after its changes, so it can add an object the pattern creates; in a `for match` every match
adds its value. Collections are values: they cannot be nodes of a pattern, and a callee never
changes the collections it was passed.

**Loops over collections.** `for x in collection do { … }` runs its block once per element. The
collection — a collection input or output, a many-valued reference such as
`placement.candidates`, or any collection expression — is evaluated once, before the first run. In
the block, `x` is a bound object when the elements are model elements (`x { … }` in the block's
patterns, `call repair(component = x)`), a model when the collection is a `List<model>` input, and
a value otherwise. It is visible in the block only, like the objects of a `for match`. An empty
collection runs the block not at all, which is a success.

**Assignments outside patterns.** `name = expression` assigns a variable of an enclosing scope or an
output, and `name += expression` adds to a collection output, as statements of their own — in a
loop's block, for instance. They count for the definite assignment of outputs like assignments in
patterns.

**`if call`.** `if call name(…) -> (…) then { … } else { … }` branches on the callee's result, as
`if match` branches on a match: the outputs are bound in the `then` block only, and the `else` block
runs when the callee fails. `stop` in the callee is a success; `kill` in the callee still aborts
everything.

<<< @/../samples/callable-transformations/move-service.mt{mt}

Since the engine cannot undo changes, the `else` block must not run on a model the callee changed
half-way. `if call` therefore only calls a transformation that **fails before it changes the
model**, which the validator checks conservatively: following the callee's statements in order, on
every path, every statement that may fail must come before every statement that may change the
model.

- A `match` may fail unless its pattern only creates objects and links, sets properties or deletes
  bound objects, and declares or assigns variables — that is, has nothing to find.
- A plain `call` may fail when its callee may fail; the callee must then satisfy the same rule.
  An `if call` inside the callee does not fail.
- A loop counts as running its block after the block's own changes, so a block that changes the
  model and may fail is rejected.
- `if match`, `for match`, `while match` and `stop` do not fail; `kill` is not a failure either.

The error names the statement that may fail and the change before it, e.g. *`'unsafe' can fail at
line 10 of unsafe.mt (a match that may find nothing) after the change at line 5 of unsafe.mt`*.
Should a failing callee have changed the model anyway, the engine reports the failure instead of
running the `else` block.

<a id="optional-element-inputs"></a>

**Optional element inputs.** A nullable element input `in placement: Placement?` whose name is that
of an object of the **first pattern** — the pattern of the first statement, a `match`, `if match`,
`for match` or `while match` — *pins* that object (story diagrams' "maybe-bound" objects): a caller
that passes a placement binds the object to it, and when none is passed, or `null`, the pattern
matches the object freely. The same file thus works as a mutation operator, where nothing is passed,
and as a callee. The object's class must be the input's class or a superclass of it, the object
cannot be one the pattern creates, and the input is optional: a call or a configuration may leave it
out. A required (non-nullable) element input is bound when the transformation starts, so no pattern
may declare an object of its name — reference it as `name { }`.

**Layered metamodels.** A callee's metamodel must be the caller's metamodel or one that the caller's
metamodel imports (directly or not). A transformation written for a base metamodel — here
`architecture.mm` — therefore runs inside a transformation of an extending metamodel —
`microservices.mm` — and its patterns also match the subclasses that metamodel adds. The reverse is
rejected. Model arguments follow the same rule: a callee written for the base metamodel reads models
of the extending one.

**Checks in the editor.** The validator reports, on the line concerned:

- imports of files that do not exist or are not transformations, aliases that collide with another
  alias or another name of the file, and — as a warning — a file imported twice;
- arguments that name no input of the callee, an output passed as an argument or an input bound
  as an output, and arguments or outputs given twice;
- missing required inputs (an `in` declaration without default whose type is not nullable, and every
  model input);
- argument types that are not assignable to the input's type (a subclass of an element input's
  class is fine, and so is a collection of a subclass);
- callees of an incompatible metamodel;
- import cycles: recursive calls are not supported;
- defaults on outputs, and outputs that are not assigned on every successful path (at the end and at
  every `stop`);
- assignments to inputs, `+=` on anything but a collection output, values of the wrong
  type for `=` and `+=`, and inputs, outputs or loop variables used as pattern nodes although
  they are scalars, collections, unbound outputs or nullable;
- loops over expressions that are not collections;
- `if call` of a transformation that may fail after it changed the model;
- nullable element inputs that pin no object of the first pattern, or one of another class;
- every rule of [model inputs](#model-inputs).

Input, output, alias and loop-variable names take part in the file-wide uniqueness of names (an
optional element input shares its name with the object it pins). Hover over a call, an import, an
argument or an output shows the callee's `in` and `out` declarations or the declaration concerned;
*Go to definition* on an alias or a call opens the callee. Completion offers the callee's inputs
inside the parentheses and its outputs after `->`.

**As operators.** A transformation listed in the [`search` section](/plugins/config-mdeo#the-search-section)
is called by the optimizer. A **mutation operator** must not have model inputs, and every required
input must be passed by the configuration in a [`with { }` block](/plugins/config-mdeo#operator-arguments);
inputs it does not pass take their defaults, optional inputs are null — optional element inputs
leave their objects free — and outputs are ignored. A **crossover operator** has model inputs; see
[Model inputs](#model-inputs). List the wrapper (`move-with-stores.mt`), not its callees: the wrapper
is one operator in the random choice of operators, so the move and its repair always happen
together.

A file with required inputs cannot be run on its own with the **Run** action either; it only runs
when another transformation, or the optimizer, calls it.

#### Model inputs

A transformation always runs on one model, the **transformed model**: the model it is applied to,
the only one it changes. It may in addition read other models of its metamodel, declared as **model
inputs**. This is what a [crossover operator](/plugins/config-mdeo#crossover-operators) is: during an
optimisation it runs on a copy of one parent, which becomes the child, and reads the other parents —
the *donors* — through its model inputs.

<<< @/../samples/crossover/take-tier.mt{mt}

| Declaration | Meaning |
| --- | --- |
| `in donor: model` | One input model |
| `in a: model` and `in b: model` | Several, one declaration each |
| `in donors: List<model>` | Any number of input models |

`model` is the name of a built-in type, like `int`: a model of the file's metamodel. It is only
written as the type of an input, `model` or `List<model>`. A model input is always required, so
`model?`, a default and `Set<model>` are rejected, and so are `out` declarations of models and
variables holding a model (`x = donor`). A model value — a model input, or the variable of a loop
over a `List<model>` — can only be

- the target of a **scope block** `in donor { … }`,
- an argument of a call, for a `model` or `List<model>` input of the callee,
- the collection of `for d in donors do { … }`, which makes `d` a model, and
- asked `donors.size()`, `donors.isEmpty()` or `donors.notEmpty()`.

Anything else — comparing models, member access, passing a model to an element or scalar input — is
a type error.

**Scope blocks.** Inside any pattern — of a `match`, `if match`, `for match`, `while match` or
`until match` — the objects of an input model are declared in a block `in name { … }`. Everything
outside a scope block belongs to the transformed model, exactly as in a transformation without model
inputs:

| Inside `in donor { … }` | |
| --- | --- |
| Objects, links, references to objects of the same model, `var`, `where` | ✅ — they find and read objects of `donor` |
| `forbid { … }` / `require { … }` | ✅ — the condition graph lives in `donor` |
| `create` / `delete` on objects or links, `delete name`, assigning a property with `=` | ❌ — input models are read-only |
| Links to objects outside the block | ❌ — no link connects objects of different models |
| Another scope block | ❌ — scope blocks do not nest |

A pattern may hold several scope blocks, for the same model or for different ones, and a scope block
may stand inside a `forbid` or `require` block — `forbid { in donor { … } }` — with the same meaning
as a condition inside the scope block: the condition graph is looked for in that model.

A scope block decides where its objects are found, not where their names are visible: the objects
it declares are bound by the match like the pattern's other objects, so they can be **read**
anywhere after they are bound — in `where` clauses, in property assignments of the transformed
model's objects (`create n: Component { name = dc.name }`), in `var`s and in scalar outputs. As
nodes of a pattern they appear only in scope blocks of their model: `dc { … }` in a later
statement is written inside `in donor { … }` again, and an object of the transformed model is never a
node inside a scope block.

**Element inputs and outputs denote objects of the transformed model.** An object of an input model
is never passed to an element input of a call, assigned to an element output, added to an element
collection output, or used as the source of the elements of a `for … in` loop. Pass the values you
need — a name, a number — instead.

**Correlating models.** Objects of different models have no identity in common: the engine knows no
correspondence between an object of the donor and one of the transformed model. Correlate them
through their attributes, as `name == dc.name` does above. Injective matching is unaffected: objects
of different models never match the same element, and within one model the usual rules apply.

**Several models.** With `in donors: List<model>` the number of models is open; loop over them and
open a scope block per model:

<<< @/../samples/crossover/follow-donors.mt{mt}

A call passes models on: `call f(source = donor)` passes a model input, `call f(source = d)` the
variable of a loop over a `List<model>`, and `call g(all = donors)` the list. The callee reads them
and still changes the caller's transformed model. A model input must always be passed — there is no
default — so a file with model inputs cannot be run with the **Run** action; it runs as a crossover
operator or when another transformation calls it. The configuration cannot pass models either: in an
optimisation the optimizer binds the donors.

::: info Reserved words
`in`, `out`, `call`, `import` and `as` are keywords of the language, besides the keywords of patterns
and statements. A name that collides with one of them is written in backticks, e.g. `` `out` ``.
`model` is not a keyword but the name of a built-in type, like `int` and `string`.
:::

::: info Not yet supported
Recursion is not supported. There is no `try call` that undoes a failed callee's changes; `if call`
is limited to callees that fail before changing the model instead. Input models are read-only: there
are no `out` or `inout` models, so a transformation cannot change a second model or produce one. There
is no correspondence between objects of different models (such as "`c` is the same component as
`dc`") other than equal attribute values. Arguments from a configuration are passed with
[`with { }`](/plugins/config-mdeo#operator-arguments).
:::

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
| `typed-ast` | The type-annotated AST the execution service interprets; the typed ASTs of the transformations it calls are bundled into it |
| `model-transformation-text` | The textual rendering of a generated `.mt_gen` file |

**Execution.** A transformation can be run against a model. The plugin forwards the request to the
`model-transformation-execution` service, configured through
`MODEL_TRANSFORMATION_EXECUTION_SERVICE_URL`.

**Environment variables.** The engine leaves TinkerPop's `PathRetractionStrategy` and
`FilterRankingStrategy` out of its traversals: a match traversal selects every pattern label at its
end, so path retraction frees almost nothing while its analysis grows with the square of the
traversal's length, and the match planner already orders the constraints. Path labels then live
until a traversal ends. Set `MDEO_MT_DEFAULT_STRATEGIES=1` (or the JVM system property
`mdeo.mt.defaultStrategies=true`) on the services that run transformations
(`model-transformation-execution` and the optimizer nodes) to keep both strategies, for example if
memory per traverser matters more than match time. It is read once, at startup.

## Graphical editor

Transformations have a diagram editor too. Pattern objects and links appear as nodes and edges, with
their modifier reflected in the styling, so the effect of a rule can be read at a glance. Element
inputs, call outputs and loop variables appear as bound objects in the patterns that use them.

The declarations are drawn where they take effect: the start node lists the `in` declarations and
every successful end — the end after the last statement and each `stop` — lists the `out`
declarations, one row each, written as in the source (`in maxCandidates: int = 0`,
`out moved: Component`). A `kill` lists none, since it discards the result. The rows are plain
text next to the node's symbol, without a frame: the inputs above the start dot, the outputs below
the end bullseye. Imports are not drawn; see them in the textual editor.

Double-click a row (or select it and press F2) to edit the declaration: a new name is renamed
wherever the file uses it — for an optional element input also the object of the first pattern it
pins — and the type and default are replaced as typed; the editor refuses a line that does not
parse, whose name is taken, or that turns an input into an output or back. The outputs shown at
several ends are the same declarations, so editing a row at any end edits the single header line.
Select a row and press Delete (or empty it) to remove the declaration. Deleting a declaration also
deletes the pattern nodes that use it as an object (`placement { }`, `delete placement`) with their
links, like deleting an instance; its other uses — in expressions, call arguments, scope blocks —
stay and are reported by the validation. Errors and warnings of an input are shown on the start
node, those of an output on the end after the last statement (or, without one, on the first
`stop`).

Select the start node for the actions **Add Input**, **Add Model Input** and **Add Model List
Input**, which add a row with a fresh name — `in input1: int`, `in donor1: model`,
`in donors1: List<model>` — and a successful end for **Add Output**, which adds `out output1: int`.
The toolbox's **Declarations** group has the same items (its **Output** adds the row to the end
after the last statement and is missing when the transformation never ends successfully). Each
opens the row for editing with the name selected; Enter or clicking elsewhere keeps it, Escape discards it. The line goes where the
textual editor's formatting puts it: inputs after the other inputs, outputs last, all after the
imports. **Import…** opens a dialog to pick a `.mt` file of the project and, optionally, the alias,
which defaults to the file name in camel case (`move-component.mt` becomes `moveComponent`),
numbered when taken; the import goes after the other imports.

The elements of a [scope block](#model-inputs) stay in the node of their pattern and are tagged with
the model they belong to, `«in donor»`, the way the members of a `forbid` or `require` block are
tagged — combined with the block's tag where both apply, e.g. `«forbid 1, in donor»`. The
**In Model** entry of the **Change Modifier** menu moves an element, together with everything
connected to it, into a scope block of the pattern, into a new one for any model input or loop
variable over a `List<model>` in scope, or back to the **Transformed Model**. It only offers moves
whose result validates. Elements of an input model offer no *Create* or *Delete*, a link drawn
between two of them carries no modifier, and no link joins two models: the editor does not let you
draw or reconnect one. Moving the last element out of a scope block, or deleting it, removes the
block.

When the transformation has model inputs, the toolbox shows **Create in** below the creation modes:
the *transformed model* (the default) or one of the model values — each `model` input, and the
variable of each `for … in` loop over a `List<model>` input. With a model selected, a new pattern
object goes into the target match's scope block of that model, or into a new `in donor { }` block
when the match has none; in *Forbid* or *Require* mode into a new `forbid { in donor { … } }` (or
`require`) block. *Create* and *Delete* are disabled, since input models are read-only, and **Add
Instance** lists the objects of that model, to reference them in its scope block. A loop variable
is only accepted in a match inside its loop; dropping into any other match creates nothing.

A call is a node on the control flow, drawn with a double left edge, that shows the call's text.
Double-click the text to edit the call's arguments and outputs. **Insert Statement** on a control
flow edge offers **Call** and **If Call** for every imported transformation (inserted without
arguments; the validator lists the inputs to pass), and deleting a call node deletes the call — for
an `if call`, the whole statement with its blocks. An `if call` branches into *success* and
*failure* edges like the *then* and *else* edges of `if match`. A `for … in` loop is a node with its
body on an *each* edge, and an assignment outside a pattern is a node with its text. Loop heads and
assignments are edited in the textual editor.
