# Plugins

Everything MDEO Cloud can do with a file comes from a plugin. The platform ships with nine, and they
use exactly the contract described in [the developer guide](/develop/) — nothing about them is
privileged.

Each plugin page below follows the same structure: what it is for, the languages it contributes, the
contribution plugins it contributes to *other* languages, what it depends on, and where its code
lives.

## The bundled plugins

| Plugin | Languages | Contributes to | Purpose |
| --- | --- | --- | --- |
| [Metamodel](/plugins/metamodel) | `metamodel` (`.mm`) | Config | Define the structure of a domain |
| [Model](/plugins/model) | `model` (`.m`), `model_gen` (`.m_gen`) | — | Instantiate a metamodel |
| [Model Transformation](/plugins/model-transformation) | `model-transformation` (`.mt`), `model-transformation_gen` (`.mt_gen`) | — | Rewrite models; the mutation and crossover operators of a search |
| [Script](/plugins/script) | `script` (`.fn`) | Config | Objectives, constraints and helper logic |
| [Config](/plugins/config) | `config` (`.config`) | — | The host language for configuration sections |
| [Config Optimization](/plugins/config-optimization) | `config-optimization` (generated) | Config | The `problem` and `goal` sections |
| [Config MDEO](/plugins/config-mdeo) | `config-mdeo` (generated) | Config | The `search`, `solver` and `runtime` sections |
| [CSV](/plugins/csv) | `csv` (`.csv`) | — | Tabular data files |
| [Model CSV](/plugins/model-csv) | `model-csv` (generated) | Model | Build a model from CSV files |

## How they fit together

| Relationship | Example |
| --- | --- |
| A file names the metamodel it is written against | `plan.m`, `assign.mt` and `objectives.fn` each start with `using "./tasks.mm"` |
| A file imports declarations from another file | `optimize.config` has `import { unassignedEffort } from "./objectives.fn"` |
| A file imports data from another file | `plan.m` has `import CSV { Task from "./tasks.csv" }` |
| A plugin exports its AST types to other contribution plugins | Metamodel exports `Class` and `Property`; Script exports `Function` |
| A plugin contributes sections to the config language | Config Optimization contributes `problem` and `goal`; Config MDEO contributes `search`, `solver` and `runtime` |
| A contribution plugin depends on another one's computed data | Config MDEO needs the `problem` section of Config Optimization before it can resolve class names |

Three kinds of relationship appear here:

- **`using` / `import` in source files.** A model, a transformation and a script each name the
  metamodel they are written against; a config file imports functions from a script file. These are
  ordinary cross-file references resolved by the language servers.
- **Type exports between contribution plugins.** The metamodel and script plugins contribute nothing
  syntactic to the config language, but they export their AST types so that the plugins which *do*
  contribute syntax can refer to metamodel classes and script functions.
- **Section contributions.** Config Optimization and Config MDEO each hand the config language a
  grammar and a list of sections. Config MDEO further declares a *section dependency* on the
  `problem` section of Config Optimization, because it needs to know the metamodel before it can
  resolve class names inside `search`.

## What a plugin looks like from outside

A plugin is a URL. The backend fetches `GET <url>/` and gets back a
[manifest](/develop/manifest) — a JSON document listing the languages and contributions. Static
assets referenced from that manifest (`language.js`, `editor.js`, `styles.css`) are served by the
same service and imported by the workbench as ES modules.

The bundled plugins are reachable at `/plugin/<name>` behind the workbench's reverse proxy, which is
what the default `DEFAULT_PLUGIN_URLS` refers to. All nine are started and registered by every
deployment: the three compose files and the Terraform stack.

## Writing your own

Start at [The extension model](/develop/), then [Add a plugin](/develop/add-a-plugin).
