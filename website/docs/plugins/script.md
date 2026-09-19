# Script plugin

A small imperative language for computing over models. Its main job is supplying the objective and
constraint functions of an optimisation, but scripts can also be run on their own.

## At a glance

| | |
| --- | --- |
| **Plugin id** | `script-service` |
| **Display name** | Script |
| **Description** | Language support for script definitions (the manifest string still says `.s files`; the extension is `.fn`) |
| **Default URL** | `/plugin/script` |
| **Source** | `app/packages/service-script`, `app/packages/language-script`, `app/packages/language-expression` |
| **Depends on** | The [Metamodel plugin](/plugins/metamodel); executions go to `script-execution` |

## Languages contributed

| Language id | Name | Extension | Textual editor | Graphical editor | Generated |
| --- | --- | --- | --- | --- | --- |
| `script` | Script | `.fn` | ✅ | ❌ | ❌ |

### The script language

A `.fn` file optionally names a metamodel, may import functions and records from other script files,
and defines functions and records.

<<< @/../samples/task-allocation/objectives.fn{fn}

#### Functions

```fn
fun name(parameter: Type, other: Type): ReturnType {
    return value
}
```

The return type may be omitted for a function that returns nothing, or written as `void`.

A parameter can have a **default value**, used when a call leaves the parameter out. It may refer to
the parameters declared before it:

```fn
fun scale(value: double, factor: double = 2.0, offset: double = factor / 2): double {
    return value * factor + offset
}
```

Arguments are passed **by position or by name**. Named arguments come after positional ones, may
be in any order, and are evaluated in the order they are written:

```fn
scale(1.0)                          // factor = 2.0, offset = 1.0
scale(1.0, offset = 0.0)
scale(offset = 0.0, value = 1.0)
```

Named arguments work for every function and method, with the parameter names their signatures
declare, except for functions taking a variable number of arguments, such as `listOf`, and lambdas.

Functions and records from another file are imported by name, and either can be renamed. A renamed
record is known by its new name only, both as a constructor and as a type, so two records of the
same name from different files can be used side by side:

```fn
import { unassignedEffort, maxOverload as overload } from "./objectives.fn"
import { Point as GeoPoint } from "./geometry.fn"
```

#### Records

A **record** is a data structure with named fields, declared like a constructor. Fields can have
default values, like parameters:

```fn
record Point(x: double, y: double = 0.0, label: string = "")
```

A record is created by calling its name, with positional or named arguments. Its fields are read
and assigned like properties, and `with` copies it with some fields changed:

```fn
val p = Point(1.0, label = "start")
p.y = 2.0
val q = p.with(x = 3.0)             // Point(x=3.0, y=2.0, label=start)
```

- Records are compared by content with `==`, and by identity with `===`. Two records are equal
  when they are of the same record and all their fields are equal, so equal records are one element
  of a set. Changing a record that is an element of a set or a key of a map does not move it there.
- `with` copies only the record itself: a list field of the copy is the same list as the original's.
- A record can be used as a type anywhere a type is expected, including `Point?`, and checked with
  `is` and `as`.
- A field cannot be named `with`, and a record cannot have the name of a function of its file or of
  a class or enum of the metamodel.

Contributions can define records of their own, which scripts use the same way.

<<< @/../samples/language-tour/records.fn{fn}

A larger example spreads records and functions over three files that import each other. It uses
default values that refer to earlier fields, named arguments to imported and renamed functions,
`with`, `val`, record equality in a set, and `?.` on an optional record field:

::: code-group

<<< @/../samples/delivery-tours/geometry.fn{fn} [geometry.fn]

<<< @/../samples/delivery-tours/tours.fn{fn} [tours.fn]

<<< @/../samples/delivery-tours/planning.fn{fn} [planning.fn]

:::

`plan()` reports the three tours, shortest first:

```text
west (empty): 0.0 km, 0.0 min, longest stop none
south: 10.0 km, 40.0 min, longest stop harbour
north: 20.0 km, 100.0 min, late, longest stop market
```

#### Reaching the model

A script that declares `using "./tasks.mm"` gets one accessor per class in that metamodel:
`Task.all()` returns every `Task` in the model being evaluated. From there you navigate through the
properties and associations the metamodel declares.

```fn
for (task in Task.all()) {
    if (task.assignee == null) { }
}
```

#### Types

| Category | Types |
| --- | --- |
| Numbers | `int`, `long`, `float`, `double` |
| Other primitives | `string`, `boolean` |
| Collections | `Collection<T>`, `List<T>`, `Set<T>`, `Bag<T>`, `OrderedSet<T>`, `Iterable<T>` |
| Domain | Every class and enum of the imported metamodel, and every record the file declares or imports |
| Lambdas | `(A, B) => R` |
| Top type | `Any` |

A `?` suffix makes a type nullable: `Shape?`, `(Int) => Int?`.

#### Statements and expressions

<<< @/../samples/language-tour/expressions.fn{fn}

Statements are variable declarations, assignments, `if` / `else if` / `else`, `while`, `for … in`,
`break`, `continue` and `return`. Statements are separated by line breaks, not semicolons.

A variable is declared with `var`, which can be assigned again, or `val`, which cannot. A `val`
without an initial value must be assigned exactly once before it is read, as in Kotlin: on each
branch that does not end in `return`, `break` or `continue`, but not in a loop or a lambda.

```fn
var count = 0
count = count + 1
val limit: int
if (strict) {
    limit = 10
} else {
    limit = 100
}
```

Operators, from tightest to loosest binding:

| Group | Operators |
| --- | --- |
| Postfix | `.`, `?.`, `!!`, calls |
| Unary | `!`, `-` |
| Cast | `as`, `as?` |
| Multiplicative | `*`, `/`, `%` |
| Additive | `+`, `-` |
| Elvis | `??` |
| Type check | `is`, `!is` |
| Relational | `<`, `>`, `<=`, `>=` |
| Equality | `==`, `!=`, `===`, `!==` |
| Conjunction | `&&` |
| Disjunction | `\|\|` |
| Conditional | `? :` |

Lambdas are written `(a, b) => expression` or `(a, b) => { … }` and are ordinary expressions.

The standard library provides `println`, the collection constructors `listOf`, `setOf`, `bagOf`,
`orderedSetOf`, and their empty counterparts `emptyList`, `emptySet`, `emptyBag`, `emptyOrderedSet`.

#### Objective and constraint functions

A function used as an objective or a constraint in a `.config` file has to satisfy two rules,
enforced by validation in the editor:

- it takes **no parameters** — it reads the model through the `all()` accessors;
- it returns a **numeric** type (`int`, `long`, `float` or `double`).

For a constraint, `0` means satisfied and any larger value is the magnitude of the violation.

## Contribution plugins contributed

| Target language | What it adds |
| --- | --- |
| `config` | The script `Function` type export |

### Script functions for the config language

Like the metamodel plugin, the script plugin contributes no syntax to the config language. It exports
its `Function` AST type so that contribution plugins which do add syntax can write rules referencing
script functions — which is how `minimize unassignedEffort` in a `goal` section resolves to the
function in your `.fn` file.

| | |
| --- | --- |
| **Contribution plugin id** | `config-script` |
| **Short name** | `script` |
| **Sections** | none |
| **Exported types** | `Function` |

### Contributions the script language accepts

The script language is itself extensible. A plugin can register a
[script contribution plugin](/develop/script-contributions) that adds:

- **functions** — extra entries in the global scope, with signatures and an implementation given as a
  typed AST, or answered by the plugin's own service;
- **records and opaque classes** — values its functions exchange with scripts;
- **expressions** — new syntax, backed by a grammar rule and implemented by a function.

Nothing in the bundled set uses this yet, but it is the supported way to grow the standard library
without changing the script language.

## Server-side capabilities

| File data key | Contents |
| --- | --- |
| `ast` | The serialised AST |
| `typed-ast` | The type-annotated AST the execution service interprets |

**Execution.** A script function can be run against a model. The plugin forwards the request to the
`script-execution` service, configured through `SCRIPT_EXECUTION_SERVICE_URL`, which runs it in a
sandboxed subprocess under a timeout.
