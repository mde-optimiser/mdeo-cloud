# Core concepts

These terms come up on every other page, so it is worth pinning them down once.

## Project

A **project** is the unit of work and of access control. It owns a file tree, a set of enabled
plugins, a list of members with permissions, and the history of executions started from it.

Files in a project are plain text and are addressed by their path inside the project. Cross-file
references — a model importing its metamodel, a config importing objective functions — are written as
relative paths and resolved inside the project.

## Plugin

A **plugin** is an HTTP service that publishes a manifest describing what it contributes. It is the
only extension mechanism the platform has: languages, editors, and extensions to other languages all
arrive through plugins.

An administrator registers a plugin once by URL. The backend fetches its manifest, stores it, and can
mark the plugin as *default*, meaning new projects get it automatically. Project administrators then
enable or disable the registered plugins per project.

A plugin contributes two kinds of thing.

### Language plugin

A **language plugin** adds a language: an id, a display name, an optional file extension, an icon, a
language server, and up to two editors — a textual one (Monaco, with a syntax highlighter and a
language configuration) and a graphical one (a diagram editor built on GLSP/Sprotty).

Some languages are marked *generated*. Those have no file extension you type into and no textual
editor; they exist so the platform can display machine-produced artefacts such as the `.m_gen`
solution models an optimisation run emits.

### Contribution plugin

A **contribution plugin** extends a language that another plugin owns. It names the target language
and hands it a payload that the target language knows how to interpret.

Two languages accept contributions today:

- The [Config language](/plugins/config) accepts **sections**. The config language on its own has no
  syntax at all — every `problem`, `goal`, `search`, `solver` and `runtime` block comes from a
  contribution plugin, together with the grammar that parses it.
- The [Script language](/plugins/script) accepts **functions and expressions**, so a plugin can grow
  the standard library or add new expression syntax.

This is what makes the platform genuinely extensible rather than merely configurable: a new plugin
can teach an existing language new syntax without that language knowing it exists.

## Language server

Each language ships a Langium-based language server. The workbench loads all of them into a single
web worker so they share one document store and can resolve references across languages — a config
file can see the functions of a script file, which in turn sees the classes of a metamodel.

The same language server code also runs server-side, inside the plugin's own service, where it is
used to answer *file data* requests.

## File data

**File data** is a derived, cached artefact computed from a file by the plugin that owns its
language: a serialised AST, a typed AST ready for execution, the data an optimisation needs. The
backend asks the plugin to compute it, tracks which files and which other file data each computation
depended on, and invalidates entries when a dependency changes.

## Execution

An **execution** is a run started from a file — running a script, applying a transformation, or
optimising a model. The backend creates the execution record and forwards it to the plugin that owns
the file's language, which in turn dispatches it to the matching execution service.

Executions stream progress to the workbench over a WebSocket and, when finished, expose a tree of
result files. An optimisation run produces a markdown summary, a JSON report, and one generated model
per solution on the Pareto front.

## Metamodel, model, transformation, objective

The four modelling concepts, in the order you meet them:

- A **metamodel** describes the shape of your domain: classes with typed properties, enums,
  inheritance, and associations with multiplicities and composition.
- A **model** is an instance of a metamodel: named objects with property values, linked together.
- A **model transformation** is a rule that matches a fragment of a model and rewrites it. During a
  search, transformations are the mutation and crossover operators — the moves the algorithm may
  make.
- An **objective** is a function that scores a model. Minimised or maximised, several at a time.
  A **constraint** is the same kind of function, but returning `0` when satisfied and a larger value
  the worse the violation is.

## Search

Optimisation uses multi-objective evolutionary algorithms — NSGA-II by default. A population of
models is mutated by applying transformations, optionally after a crossover that combines several
parents, each candidate is scored by the objective functions, and the best trade-offs survive. The result is not one answer but a **Pareto front**: the set of
solutions where improving one objective necessarily worsens another.
