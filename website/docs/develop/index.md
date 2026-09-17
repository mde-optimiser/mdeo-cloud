# The extension model

MDEO Cloud has exactly one extension mechanism: the plugin. Languages, editors, validations, type
systems, execution backends and even the syntax of existing languages all arrive through it. The nine
bundled plugins are ordinary plugins — there is no privileged path they take that yours cannot.

This section describes the contract. If you want to see it applied, every page links to the bundled
plugin that exercises the feature.

## What a plugin is

**A plugin is an HTTP service that answers `GET /` with a manifest.** That is the whole external
contract. The manifest declares:

- **language plugins** — new languages, each with an id, an optional file extension, an icon, a
  language server module, and up to two editors;
- **contribution plugins** — extensions to languages that *other* plugins own.

Everything else follows from those two lists. The backend stores the manifest, the workbench imports
the referenced ES modules, and the language servers of all enabled plugins end up in one shared
Langium environment.

```
   plugin service
   ├── GET  /                        → manifest
   ├── GET  /static/…/language.js    → the Langium language plugin (ES module)
   ├── GET  /static/…/editor.js      → the GLSP editor configuration (ES module)
   ├── GET  /static/…/styles.css     → editor styles
   ├── POST /data/:language/:key     → compute file data
   ├── POST /request/:language/:key  → language-specific request
   ├── POST /:language/executions    → start an execution
   ├── WS   /ws/executions           → execution results and progress
   └── WS   /ws/sessions/:kind/:targetId/:name → a session with an execution
```

## The two kinds of contribution

### A language plugin adds a language

You get a file extension, an icon, a textual editor with your own syntax highlighting, optionally a
diagram editor, and a Langium language server that runs both in the browser and inside your service.

Read [Add a language](/develop/add-a-language) and [The grammar DSL](/develop/grammar).

### A contribution plugin extends someone else's language

This is the part that makes the platform a platform. A language can declare that it accepts
contributions, and other plugins can then extend it — including its **grammar**.

Two languages accept contributions today:

| Language | What can be contributed |
| --- | --- |
| [Config](/plugins/config) | Sections: a keyword, a grammar for its body, and whether it is executable |
| [Script](/plugins/script) | Functions in the global scope, and new expression syntax |

The config language is the clearest example, because it has *no syntax of its own*. Everything you
can write in a `.config` file comes from a contribution plugin, and which sections exist depends on
which plugins a project has enabled.

Read [Contribution plugins](/develop/contribution-plugins).

## Why grammars are serialisable

Shipping a grammar to the workbench needs nothing special — `language.js` is an ES module, so a parser
generated at build time would load there perfectly well.

What generated parser code cannot do is **composition**. A contribution plugin's rules have to end up
inside a *different* plugin's parser, chosen per project, and they have to get there as data in a
manifest. Rules built with `createRule` can be serialised to JSON, carried across that boundary, and
deserialised in a context that supplies the types and rules they refer to — which is exactly what the
config language does when it assembles its grammar from the contribution plugins a project has
enabled.

Since the DSL is needed for that case anyway, every language uses it, so the same rules work whether a
grammar stands alone or is embedded into another one.

Read [The grammar DSL](/develop/grammar).

## Where to start

| I want to… | Read |
| --- | --- |
| Understand how a plugin service is put together | [Anatomy of a plugin](/develop/plugin-anatomy) |
| Know exactly what the manifest must contain | [Plugin manifest reference](/develop/manifest) |
| See every hook a language can implement | [Extension points](/develop/extension-points) |
| Build a plugin from scratch | [Add a plugin](/develop/add-a-plugin) |
| Add a language to an existing plugin | [Add a language](/develop/add-a-language) |
| Extend the config or script language | [Contribution plugins](/develop/contribution-plugins) |
| Know which package a piece of code belongs in | [Packages and runtime dependencies](/develop/package-structure) |
| Add a diagram editor | [Graphical editors](/develop/graphical-editors) |
| Call a plugin service directly | [Language service HTTP API](/develop/service-api) |
| Run all of this locally | [Local development](/develop/local-development) |
