import { TextDocument } from "langium";
import type { LangiumDocument } from "langium";
import { collectDiagnostics, findDependenciesWithErrors, hasErrors } from "@mdeo/service-common";
import type { RequestContext, RequestHandler } from "@mdeo/service-common";
import type { ConfigPluginDiagnosticsResponse, ConfigPluginRequestBody } from "./configPluginTypes.js";

/**
 * Discriminated union returned by {@link buildConfigPluginDocument}.
 *
 * - `"empty"` – the request had no text content or the parsed document had no
 *   sections; callers should return an empty (but valid) response.
 * - `"error"` – Langium validation produced errors; callers should return
 *   `data: null` while still including tracked dependencies.
 * - `"success"` – the document was built and validated successfully; callers
 *   can walk {@link ConfigPluginDocumentSuccess.sections}.
 */
export type ConfigPluginDocumentResult =
    | { type: "empty" }
    | { type: "error" }
    | {
          type: "success";
          /**
           * The built Langium document.
           */
          document: LangiumDocument;
          /**
           * Top-level section nodes from the parsed AST.
           */
          sections: any[];
          /**
           * The decoded request body supplied by the config service.
           */
          requestBody: ConfigPluginRequestBody;
      };

/**
 * Builds and validates a partial config document for a contribution plugin.
 *
 * This helper encapsulates the common boilerplate shared by all config
 * contribution plugin request handlers:
 * 1. Decodes the {@link ConfigPluginRequestBody} from the request context.
 * 2. Short-circuits to `"empty"` when no text is present.
 * 3. Creates a synthetic `TextDocument`, registers it with the Langium
 *    workspace, and creates a `LangiumDocument` via the document factory.
 * 4. Optionally runs caller-provided **setup** logic (e.g. injecting a
 *    metamodel resolver) inside the `try/finally` block so that cleanup
 *    is always executed.
 * 5. Builds and validates the document via `DocumentBuilder`.
 * 6. Returns `"error"` when validation errors are present.
 * 7. Returns `"success"` with the document and its top-level sections.
 *
 * @param context  The request context injected by the service framework.
 * @param languageKey  The language ID of the target contribution plugin language.
 * @param setup  Optional callback invoked after document creation and before
 *   `DocumentBuilder.build`. Runs inside the `try/finally` block so
 *   {@link cleanup} is guaranteed to execute even if `setup` throws.
 * @param cleanup  Optional callback invoked in the `finally` block after
 *   `DocumentBuilder.build`, regardless of success or failure. Useful for
 *   releasing resources such as metamodel resolver state.
 * @returns A discriminated {@link ConfigPluginDocumentResult}.
 */
export async function buildConfigPluginDocument<S extends object>(
    context: RequestContext<S>,
    languageKey: string,
    setup?: (requestBody: ConfigPluginRequestBody) => void,
    cleanup?: () => void
): Promise<ConfigPluginDocumentResult> {
    const requestBody = context.body as ConfigPluginRequestBody;
    const document = await buildPartialDocument(context, languageKey, setup, cleanup);
    if (document == undefined) {
        return { type: "empty" };
    }
    if (hasErrors(document)) {
        return { type: "error" };
    }

    const root = document.parseResult?.value as { sections?: any[] } | undefined;
    if (root == undefined || !Array.isArray(root.sections)) {
        return { type: "empty" };
    }

    return { type: "success", document, sections: root.sections, requestBody };
}

/**
 * Creates the handler for {@link CONFIG_DIAGNOSTICS_REQUEST_KEY} requests of a contribution
 * plugin: builds the partial config document as {@link buildConfigPluginDocument} does and
 * reports its diagnostics.
 *
 * @param languageKey The language ID of the contribution plugin language
 * @param setup Optional callback run before the document is built, as for {@link buildConfigPluginDocument}
 * @param cleanup Optional callback run after the document is built, as for {@link buildConfigPluginDocument}
 * @returns The request handler
 */
export function createConfigDiagnosticsRequestHandler<S extends object>(
    languageKey: string,
    setup?: (requestBody: ConfigPluginRequestBody, context: RequestContext<S>) => void,
    cleanup?: (context: RequestContext<S>) => void
): RequestHandler<ConfigPluginDiagnosticsResponse, S> {
    return async (context) => {
        const document = await buildPartialDocument(
            context,
            languageKey,
            setup && ((requestBody) => setup(requestBody, context)),
            cleanup && (() => cleanup(context))
        );
        const dependenciesWithErrors =
            document == undefined
                ? []
                : await findDependenciesWithErrors(document.uri.path, context.services, context.serverApi);
        return {
            diagnostics: document == undefined ? [] : collectDiagnostics(document),
            dependenciesWithErrors,
            ...context.serverApi.getTrackedRequests()
        };
    };
}

/**
 * Creates the partial config document of a request and builds and validates it.
 *
 * @param context The request context
 * @param languageKey The language ID of the contribution plugin language
 * @param setup Optional callback run before the document is built
 * @param cleanup Optional callback run after the document is built, even if building fails
 * @returns The built document, or undefined if the request has no text
 */
async function buildPartialDocument<S extends object>(
    context: RequestContext<S>,
    languageKey: string,
    setup?: (requestBody: ConfigPluginRequestBody) => void,
    cleanup?: () => void
): Promise<LangiumDocument | undefined> {
    const requestBody = context.body as ConfigPluginRequestBody;
    const text = requestBody?.text ?? "";

    if (!text.trim()) {
        return undefined;
    }

    const textDocument = TextDocument.create(requestBody.configFileUri, languageKey, 0, text);
    context.services.shared.workspace.TextDocuments.set(textDocument);
    const document = context.services.shared.workspace.LangiumDocumentFactory.fromTextDocument(textDocument);

    try {
        setup?.(requestBody);
        await context.services.shared.workspace.DocumentBuilder.build([document], { validation: true });
    } finally {
        cleanup?.();
    }
    return document;
}
