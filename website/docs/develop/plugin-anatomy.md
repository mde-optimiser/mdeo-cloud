# Anatomy of a plugin

A plugin is delivered as one deployable Node service, usually built from three or four packages.

## Package layout

The bundled plugins all follow the same split:

| Package | Runs where | Contains |
| --- | --- | --- |
| `language-<name>` | Browser **and** service | Grammar, scoping, validation, type system, serializers, action handlers |
| `editor-<name>` | Browser | The GLSP/Sprotty diagram editor |
| `protocol-<name>` | Both | Types shared between editor and language server |
| `service-<name>` | Service | The manifest, the served entry points, and the handlers |

Only `service-<name>` is deployed; the rest is bundled into what it serves.

[Packages and runtime dependencies](/develop/package-structure) explains why the split exists, which
base packages the platform provides, and how managed dependencies such as Langium reach your code at
runtime.

## `service-<name>` in detail

```
service-metamodel/
├── package.json
├── vite.config.ts              # builds the served ES modules into static/
├── src/
│   ├── index.ts                # the manifest and the service startup
│   ├── served/
│   │   ├── language.ts         # re-exports the Langium language plugin provider
│   │   ├── editor.ts           # re-exports the GLSP container configuration
│   │   └── gedWorker.ts        # graph-edit-distance worker, for diagram diffing
│   ├── handler/                # file data handlers
│   └── metamodelConfigContributionPlugin.ts
└── static/                     # vite build output, served over HTTP
```

### `src/index.ts`

Almost entirely declarative. It builds the language plugin definitions, assembles the
`ServicePluginDefinition` that becomes the manifest, wires up handlers, and hands everything to
`startLanguageService`.

```ts
const metamodelLanguagePlugin: LanguagePlugin = {
    id: "metamodel",
    name: "Metamodel",
    extension: ".mm",
    icon: convertIcon(Network),
    serverPlugin: { import: "language.js" },
    graphicalEditorPlugin: {
        import: "editor.js",
        stylesUrl: "styles.css",
        stylesCls: "editor-metamodel"
    },
    textualEditorPlugin: {
        languageConfiguration: defaultLanguageConfiguration,
        monarchTokensProvider: serializeMonarchTokensProvider({
            ...defaultMonarchTokenProvider,
            keywords: ["class", "extends", "abstract", "import", "from", "as", "enum"]
        })
    },
    isGenerated: false
};

const metamodelServicePlugin: ServicePluginDefinition = {
    id: "metamodel-service",
    name: "Metamodel",
    description: "Language support for metamodel definitions (.mm files)",
    icon: convertIcon(Network),
    languagePlugins: [metamodelLanguagePlugin],
    contributionPlugins: [
        {
            languageId: "config",
            description: "Provides metamodel type exports for config language",
            additionalKeywords: [],
            serverContributionPlugins: [createMetamodelConfigContributionPlugin()]
        }
    ]
};

await startLanguageService({
    ...parseServiceConfigFromEnv(),
    plugin: metamodelServicePlugin,
    languages: [metamodelLanguageConfig]
});
```

::: warning `initializePluginContext()` comes first
Language packages do not import `langium`, `typir` or the GLSP packages directly — they receive them
through a global *plugin context*, so that the workbench and the service can supply the same instances
to every plugin. Call `initializePluginContext()` **before** dynamically importing any language
package, and use `await import(...)` rather than a static import for those packages.
:::

### `src/served/`

These files are entry points of a separate Vite library build whose output lands in `static/`. They
exist because the workbench imports them over HTTP as ES modules.

```ts
// served/language.ts
import { metamodelPluginProvider } from "@mdeo/language-metamodel";
export default metamodelPluginProvider;
```

```ts
// served/editor.ts
import { metamodelEditorPlugin } from "@mdeo/editor-metamodel";
import "@mdeo/editor-metamodel/styles";
export default metamodelEditorPlugin;
```

The default export of `language.js` must be a `LangiumLanguagePluginProvider`; the default export of
`editor.js` must be a GLSP `ContainerConfiguration`.

### `LanguageServiceConfig`

One per language the service provides:

```ts
const metamodelLanguageConfig: LanguageServiceConfig<MetamodelServices> = {
    languagePlugin: metamodelLanguagePlugin,
    languagePluginProvider: metamodelPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler,
        [METAMODEL_AST_DATA_HANDLER_KEY]: metamodelAstDataHandler
    },
    requestHandlers: { /* … */ },
    executionHandlers: [ /* … */ ]
};
```

| Field | Purpose |
| --- | --- |
| `languagePlugin` | The manifest entry, reused so the two cannot drift apart |
| `languagePluginProvider` | Creates the Langium language plugin, given the active contribution plugins |
| `serviceModule` | Optional Langium module overrides that only apply server-side |
| `fileDataHandlers` | Compute derived data for a file, keyed by data key |
| `requestHandlers` | Answer arbitrary language-specific requests, keyed by request key |
| `executionHandlers` | Start and manage executions |

## What `startLanguageService` gives you

`@mdeo/service-common` implements the whole HTTP surface, so a plugin never writes an endpoint:

- the manifest at `GET /`, with static asset paths rewritten to the versioned `static/` prefix;
- static file serving with CORS and the COOP/COEP headers the workbench needs;
- JWT authentication for every request, with tokens issued by the backend and one
  [scope](/develop/service-api#scopes) per capability;
- a **pool of Langium instances**, keyed by the set of active contribution plugins, so that two
  projects with different plugin sets do not share a parser;
- dependency tracking: whatever a handler reads through the `ServerApi` is recorded as a file or data
  dependency and returned with the result, so the backend can invalidate the cache correctly;
- the WebSocket bridge for execution progress, and [sessions](/develop/sessions) with executions.

See [Language service HTTP API](/develop/service-api) for the endpoints themselves.

## The Langium side

`language-<name>` exports a `LangiumLanguagePluginProvider`:

```ts
export const metamodelPluginProvider: LangiumLanguagePluginProvider<MetamodelServices> = {
    create(contributionPlugins, languageJsUrl) {
        return {
            rootRule: MetaModelRule,
            additionalTerminals: [WS, HIDDEN_NEWLINE, ML_COMMENT, SL_COMMENT],
            module: { /* Langium service overrides */ },
            postCreate(services) { /* register validation, serializers, … */ }
        };
    }
};
```

`create` receives the contribution plugins active for the request, which is what allows a language to
build a different grammar per project. See [Extension points](/develop/extension-points) for what can
go into `module` and `postCreate`.
