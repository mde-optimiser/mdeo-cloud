# Config Optimization plugin

Contributes the `problem` and `goal` sections to the config language: which model is being optimised,
and what makes one candidate better than another.

## At a glance

| | |
| --- | --- |
| **Plugin id** | `config-optimization-service` |
| **Display name** | Config Optimization |
| **Description** | Language support for config optimization sections |
| **Default URL** | `/plugin/config-optimization` |
| **Source** | `app/packages/service-config-optimization`, `app/packages/language-config-optimization` |
| **Depends on** | The [Config](/plugins/config), [Metamodel](/plugins/metamodel) and [Script](/plugins/script) plugins |

## Languages contributed

| Language id | Name | Extension | Textual editor | Graphical editor | Generated |
| --- | --- | --- | --- | --- | --- |
| `config-optimization` | Config Optimization | — | ❌ | ❌ | ✅ |

### The `config-optimization` language

This is a *generated* language with no file extension and no editor. It exists so the plugin's own
grammar can be used as a language in its own right: when the config service needs the `problem` and
`goal` sections of a file computed, it sends just those sections to this language's service, which
parses them with a standalone grammar built from the same rules.

You never create a file in this language. Its syntax is the syntax of the sections below.

## Contribution plugins contributed

| Target language | Sections | Executable |
| --- | --- | --- |
| `config` | `problem`, `goal` | no |

| | |
| --- | --- |
| **Contribution plugin id** | `config-optimization` |
| **Short name** | `optimization` |
| **Grammar dependencies** | `config-metamodel`, `config-script` |
| **Section dependencies** | none |

The dependencies are what let this grammar refer to things it does not own: metamodel classes and
properties come from the metamodel plugin's exported types, script functions from the script
plugin's.

### The `problem` section

Names the metamodel and the model the search starts from.

```mdeo-config
problem {
    metamodel = "./shapes.mm"
    model = "./shapes.m"
}
```

| Key | Type | Meaning |
| --- | --- | --- |
| `metamodel` | path | The `.mm` file describing the domain |
| `model` | path | The `.m` file to start the search from |

Both keys are required, may appear in either order, and each may appear only once. Validation checks
that the paths resolve, that they point at files of the right language, and that the model actually
imports the metamodel you named.

### The `goal` section

States what to optimise.

```mdeo-config
goal {
    import { shapeCount, invisibleShapes, emptyLayers } from "./metrics.fn"

    minimize invisibleShapes
    maximize shapeCount
    constraint emptyLayers

    refine Canvas.layers[1..4]
}
```

| Entry | Syntax | Meaning |
| --- | --- | --- |
| Import | `import { a, b as c } from "./file.fn"` | Bring script functions into scope |
| Objective | `minimize f` / `maximize f` | Add an objective |
| Constraint | `constraint f` | Add a constraint |
| Refinement | `refine Class.end[m]` | Narrow the multiplicity of an association end for the search |

Entries may be given in any order and repeated.

**Objectives and constraints** must refer to script functions that take no parameters and return a
numeric type. A constraint returns `0` when satisfied; larger values are the degree of violation. At
least one objective is required.

**Refinements** tighten the multiplicity of an association end for the search only, without changing
the metamodel that other files share. `refine Canvas.layers[1..4]` makes every
[generated mutation operator](/guide/generated-operators#refinements) keep each canvas between one and
four layers. A refinement names the class that declares the end and can only narrow its bounds. The
initial model must already satisfy it, or the run fails before it starts; hand-written `using`
operators are not affected.

### Full example

<<< @/../samples/task-allocation/optimize.config{mdeo-config}

## Server-side capabilities

| Key | Kind | Contents |
| --- | --- | --- |
| `ast` | file data | The serialised AST of the standalone language |
| `config` | request | Computes the section data for the `problem` and `goal` sections when the config service asks for it |

The plugin declares no executable section, so it never receives an execution. Its computed section
data is consumed by [Config MDEO](/plugins/config-mdeo), which declares a section dependency on
`problem`.
