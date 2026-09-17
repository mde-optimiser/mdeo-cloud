# Plugin manifest reference

The manifest is the entire external contract of a plugin. The backend fetches it with `GET /` when the
plugin is registered, whenever an administrator refreshes it, and whenever the plugin's
`X-Mdeo-Manifest-Fingerprint` changes — which the backend sees on every answer and also asks for every
`PLUGIN_MANIFEST_CHECK_SECONDS`.

In TypeScript the shape is `Plugin` from `@mdeo/plugin`. A service builds a `ServicePluginDefinition`
— the same thing without `url` and `default`, which the backend fills in — and `@mdeo/service-common`
serialises it.

A [Kotlin plugin service](/develop/kotlin-plugin-service) builds it from a `PluginDefinition`
instead, which writes `languagePlugins` as empty and fills in each contribution's `id` and `sessions`.

## `Plugin`

```json
{
  "id": "metamodel-service",
  "name": "Metamodel",
  "description": "Language support for metamodel definitions (.mm files)",
  "icon": [["path", { "d": "M12 2v4" }]],
  "languagePlugins": [ /* … */ ],
  "contributionPlugins": [ /* … */ ]
}
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id` | string | ✅ | Unique id of the plugin |
| `url` | string | — | Filled in by the backend from the registered URL |
| `name` | string | ✅ | Display name in the workbench |
| `description` | string | ✅ | Shown in the plugin list |
| `icon` | icon node | ✅ | A [Lucide](https://lucide.dev) icon, converted with `convertIcon` |
| `default` | boolean | — | Set by an administrator; default plugins are added to new projects |
| `languagePlugins` | `LanguagePlugin[]` | ✅ | Languages this plugin provides |
| `contributionPlugins` | `LanguageContributionPlugin[]` | ✅ | Extensions to other plugins' languages |

### Icons

An icon is a serialised Lucide icon — an array of `[tag, attributes]` pairs. Use `convertIcon` from
`@mdeo/language-common` on a Lucide import, or hand-write the array when you need custom shapes:

```ts
import { Network } from "lucide";
import { convertIcon } from "@mdeo/language-common";

const icon = convertIcon(Network);
```

## `LanguagePlugin`

```json
{
  "id": "metamodel",
  "name": "Metamodel",
  "extension": ".mm",
  "newFileAction": false,
  "isGenerated": false,
  "documentationUrl": "https://mde-optimiser.github.io/mdeo-cloud/plugins/metamodel",
  "icon": [["path", { "d": "M12 2v4" }]],
  "serverPlugin": { "import": "static/language.js" },
  "graphicalEditorPlugin": {
    "import": "static/editor.js",
    "stylesUrl": "static/styles.css",
    "stylesCls": "editor-metamodel"
  },
  "textualEditorPlugin": {
    "languageConfiguration": { },
    "monarchTokensProvider": { }
  }
}
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id` | string | ✅ | Language id, unique across all enabled plugins |
| `name` | string | ✅ | Display name |
| `extension` | string | — | File extension **including the dot**. Omit for generated languages that have no files of their own |
| `newFileAction` | boolean | — | Show a dialog when a file of this language is created — used by languages that must know their metamodel up front |
| `serverPlugin.import` | string | ✅ | Path to the ES module exporting the `LangiumLanguagePluginProvider` |
| `graphicalEditorPlugin` | object | — | Omit for languages without a diagram editor |
| `textualEditorPlugin` | object | — | Omit for languages without a text editor |
| `icon` | icon node | ✅ | Icon shown for files of this language |
| `isGenerated` | boolean | ✅ | Marks a language whose files the platform produces rather than the user |
| `documentationUrl` | string | — | Documentation for the language. The workbench shows a question mark next to the editor title actions which opens it in a new tab; omit it and no such button appears |
| `sessions` | object | — | [Sessions](/develop/sessions) this language accepts, keyed by session name. Callers reach them at `lang:<id>` |

### Relative paths and versioning

Write asset paths **relative**, as `"language.js"`. `buildManifest` rewrites them to
`static/<version>/language.js` when `SERVICE_VERSION` is set, and to `static/language.js` otherwise.
The backend then resolves them against the plugin's URL.

This is why the backend fetches the manifest again after a redeploy: a stored manifest points at the
old version segment. See [After an upgrade](/guide/deployment#after-an-upgrade).

### `graphicalEditorPlugin`

| Field | Meaning |
| --- | --- |
| `import` | ES module whose default export is a GLSP `ContainerConfiguration` |
| `stylesUrl` | Stylesheet loaded when the editor opens |
| `stylesCls` | CSS class applied to the editor container, so styles can be scoped |

### `textualEditorPlugin`

| Field | Meaning |
| --- | --- |
| `languageConfiguration` | Monaco `LanguageConfiguration`: brackets, comments, auto-closing pairs |
| `monarchTokensProvider` | Monaco Monarch tokenizer, serialised |

Monarch tokenizers contain `RegExp` objects, which do not survive JSON. Serialise with
`serializeMonarchTokensProvider` before putting one in a manifest; the workbench calls
`deserializeMonarchTokensProvider` on the way back.

The usual case is to take the shared defaults and only replace the keyword list:

```ts
textualEditorPlugin: {
    languageConfiguration: defaultLanguageConfiguration,
    monarchTokensProvider: serializeMonarchTokensProvider({
        ...defaultMonarchTokenProvider,
        keywords: ["class", "extends", "abstract", "import", "from", "as", "enum"]
    })
}
```

## `LanguageContributionPlugin`

```json
{
  "languageId": "config",
  "description": "Provides optimization section support for config language",
  "additionalKeywords": ["problem", "goal", "metamodel", "model"],
  "serverContributionPlugins": [ /* language specific payload */ ]
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `languageId` | string | The id of the language being extended |
| `description` | string | Shown in the plugin details view |
| `additionalKeywords` | string[] | Keywords this contribution introduces, so the target language's syntax highlighting can pick them up |
| `serverContributionPlugins` | object[] | The payload, interpreted by the target language |

`additionalKeywords` is purely presentational — it feeds the Monarch tokenizer of the target language.
The grammar itself comes from the payload.

### `ServerContributionPlugin`

The base type has two platform-owned fields, and everything else is defined by the language being
extended, which type-guards on a `type` discriminator:

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Contribution id, unique **within a project**. This is the address callers write as `contrib:<id>`; a plugin whose id is already taken is refused with `PluginContributionIdConflict` |
| `sessions` | object | [Sessions](/develop/sessions) this contribution accepts, keyed by session name |

The discriminators of the bundled languages:

| Target | `type` | Payload type |
| --- | --- | --- |
| `config` | `config-language-contribution` | [`ConfigContributionPlugin`](/develop/config-contributions) |
| `script` | `script-language-contribution` | [`ScriptContributionPlugin`](/develop/script-contributions) |

Because the payload is plain JSON it travels through the backend without either side knowing the
other. A contribution to a language your plugin has never heard of is simply passed along.

## Complete example

The Config MDEO plugin's manifest, abridged:

```json
{
  "id": "config-mdeo-service",
  "name": "Config MDEO",
  "description": "Language support for config MDEO sections (search and solver)",
  "icon": [["path", { "d": "…" }]],
  "languagePlugins": [
    {
      "id": "config-mdeo",
      "name": "Config MDEO",
      "newFileAction": false,
      "isGenerated": true,
      "serverPlugin": { "import": "static/language.js" },
      "graphicalEditorPlugin": null,
      "textualEditorPlugin": null,
      "icon": [["path", { "d": "…" }]]
    }
  ],
  "contributionPlugins": [
    {
      "languageId": "config",
      "description": "Provides search and solver section support for config language",
      "additionalKeywords": ["search", "solver", "mutations", "algorithm", "…"],
      "serverContributionPlugins": [
        {
          "id": "config-mdeo",
          "type": "config-language-contribution",
          "name": "mdeo",
          "languageKey": "config-mdeo",
          "grammar": { "rules": [], "interfaces": [], "types": [] },
          "sections": [
            { "name": "search",  "ruleName": "SearchSectionContentRule",  "interfaceName": "SearchSection",  "executable": false },
            { "name": "solver",  "ruleName": "SolverSectionContentRule",  "interfaceName": "SolverSection",  "executable": true },
            { "name": "runtime", "ruleName": "RuntimeSectionContentRule", "interfaceName": "RuntimeSection", "executable": false }
          ],
          "dependencies": ["config-optimization"],
          "exportedTypes": [],
          "sectionDependencies": [{ "pluginName": "optimization", "sectionName": "problem" }]
        }
      ]
    }
  ]
}
```
