/**
 * Langium services injected into the GLSP server injection key.
 */
export const LanguageServicesKey = Symbol("LangiumServices");

/**
 * Langium AST reflection service injection key.
 */
export const AstReflectionKey = Symbol("AstReflection");

/**
 * Injection key for the HTTP URL of the plugin's served `language.js` file.
 * Used by the metadata manager to resolve the GED worker script URL relative
 * to the language entry point, correctly handling versioned static paths.
 */
export const GedWorkerBaseUrl = Symbol("GedWorkerBaseUrl");
