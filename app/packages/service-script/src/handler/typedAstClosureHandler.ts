import type { FileDataHandler } from "@mdeo/service-common";
import type { ScriptServices, TypedAst } from "@mdeo/language-script";
import { TYPED_AST_HANDLER_KEY } from "./typedAstHandler.js";

/**
 * Key of the file data holding a script's typed AST together with those of everything it imports.
 */
export const TYPED_AST_CLOSURE_HANDLER_KEY = "typed-ast-closure";

/**
 * A script's typed AST and the typed ASTs of every file it imports, directly or not.
 */
export interface TypedAstClosure {
    /**
     * Every typed AST, by file path; the script's own among them.
     */
    files: Record<string, TypedAst>;
}

/**
 * Computes the typed AST closure of a script, so an execution fetches one document instead of one
 * per imported file.
 *
 * The typed ASTs are read as file data, so the closure is recomputed exactly when one of them
 * changes, and the imports of each level are fetched together.
 *
 * @param context The file data context
 * @returns The closure, or null when the script or one of its imports has no typed AST
 */
export const typedAstClosureHandler: FileDataHandler<TypedAstClosure | null, ScriptServices> = async (context) => {
    const { fileInfo, serverApi } = context;
    if (fileInfo == undefined) {
        return { data: null, ...serverApi.getTrackedRequests() };
    }

    const files: Record<string, TypedAst> = {};
    let pending = [fileInfo.uri.path];
    while (pending.length > 0) {
        const loaded = await Promise.all(
            pending.map(
                async (path) => [path, (await serverApi.getFileData(path, TYPED_AST_HANDLER_KEY)).data] as const
            )
        );
        const next = new Set<string>();
        for (const [path, ast] of loaded) {
            if (ast == null) {
                return { data: null, ...serverApi.getTrackedRequests() };
            }
            const typedAst = ast as TypedAst;
            files[path] = typedAst;
            for (const typedImport of typedAst.imports) {
                if (!(typedImport.uri in files)) {
                    next.add(typedImport.uri);
                }
            }
        }
        pending = [...next].filter((path) => !(path in files));
    }

    return { data: { files }, ...serverApi.getTrackedRequests() };
};
