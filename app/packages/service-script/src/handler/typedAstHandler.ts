import {
    ExternalImplementation,
    Script,
    type ResolvedScriptContributionPlugins,
    type ScriptServices,
    type TypedAst,
    type TypedFunction,
    type TypedParameter
} from "@mdeo/language-script";
import { hasErrors, type FileDataHandler } from "@mdeo/service-common";
import { ScriptTypedAstConverter } from "./scriptTypedAstConverter.js";
import type { ReturnType } from "@mdeo/language-expression";
import { ScriptTypedAstMerger } from "./typedAstMerger.js";

/**
 * Key for the typed AST handler.
 */
export const TYPED_AST_HANDLER_KEY = "typed-ast";

/**
 * The root AST provides access to all functions contributed by plugins
 */
interface TypedRootAst {
    /**
     * Array of all types used in the plugin functions.
     * Indexed by typeIndex in expressions.
     */
    types: ReturnType[];

    /**
     * All the functions contributed by plugins
     */
    functions: TypedPluginFunction[];

    /**
     * All the classes contributed by plugins
     */
    classes: TypedPluginClass[];
}

/**
 * A record or opaque class a contribution defines.
 */
interface TypedPluginClass {
    /**
     * Id of the contribution that defines it; its type package is `contrib/<contribution>`.
     */
    contribution: string;
    /**
     * The class name.
     */
    name: string;
    /**
     * `record` or `opaque`.
     */
    kind: "record" | "opaque";
    /**
     * The fields of a record, in order, with indices into the types array. Empty for an opaque class.
     */
    fields: TypedParameter[];
}

/**
 * A plugin function with all its overloaded signatures
 */
interface TypedPluginFunction {
    /**
     * The name of the function
     */
    name: string;
    /**
     * The signatures of the function, keyed by overload identifier
     */
    signatures: Record<string, TypedPluginFunctionSignature>;
}

/**
 * A function signature without the name.
 *
 * Exactly one of `body` and `external` is set. A body is a typed AST the execution service runs
 * itself; an external implementation names an operation the plugin's own service answers over
 * its `script-functions` session.
 */
interface TypedPluginFunctionSignature {
    /**
     * Parameters of this overload, in declaration order.
     */
    parameters: TypedParameter[];
    /**
     * Index into the merged types array for the return type.
     */
    returnType: number;
    /**
     * The typed AST implementing this overload, when it is implemented in the platform.
     */
    body?: TypedFunction["body"];
    /**
     * The operation answering this overload, when it is implemented outside the platform,
     * together with the contribution and session it is answered on.
     */
    external?: ExternalImplementation & { contribution: string; session?: string };
}

/**
 * Handler for computing the typed AST of a script file.
 * Converts the language AST into a typed representation suitable for code generation.
 *
 * @param context The file data context with path, content, and services
 * @returns Promise resolving to the file data result with typed AST
 */
export const typedAstHandler: FileDataHandler<TypedAst | TypedRootAst | null, ScriptServices> = async (context) => {
    const { instance, fileInfo, serverApi } = context;

    if (fileInfo == undefined) {
        const typedRootAst = createTypedRootAst(instance.services.typir.ResolvedContributionPlugins);
        return {
            data: typedRootAst,
            ...serverApi.getTrackedRequests()
        };
    }

    const document = await instance.buildDocument(fileInfo.uri);

    if (hasErrors(document)) {
        return {
            data: null,
            ...serverApi.getTrackedRequests()
        };
    }

    const script = document.parseResult.value;
    const reflection = instance.services.shared.AstReflection;
    if (!reflection.isInstance(script, Script)) {
        throw new Error("Document root is not a Script");
    }

    const converter = new ScriptTypedAstConverter(instance.services.typir, reflection);
    const typedRoot = converter.convertScript(script, document);

    return {
        data: typedRoot,
        ...serverApi.getTrackedRequests()
    };
};

/**
 * Creates the typed root AST containing all contributed functions from plugins
 *
 * @param resolvedPlugins The resolved script contribution plugins
 * @returns The typed root AST
 */
function createTypedRootAst(resolvedPlugins: ResolvedScriptContributionPlugins): TypedRootAst {
    const functions: TypedPluginFunction[] = [];
    const merger = new ScriptTypedAstMerger();

    for (const [functionName, resolvedFunction] of resolvedPlugins.functions.entries()) {
        const signatures: Record<string, TypedPluginFunctionSignature> = {};

        for (const [overloadId, contributedSignature] of Object.entries(
            resolvedFunction.contributedFunction.signatures
        )) {
            const implementation = contributedSignature.implementation;

            // A signature names its types directly, so they go straight into the global table. Only a
            // typed AST body refers to its contribution's own types array by index.
            const parameters = contributedSignature.signature.parameters.map((param) => {
                const defaultValue = contributedSignature.defaultValues?.[param.name];
                return {
                    name: param.name,
                    type: merger.addTypeToGlobal(param.type),
                    ...(defaultValue != undefined && {
                        defaultValue: merger.remapContributedExpression(defaultValue, resolvedFunction.types)
                    })
                };
            });
            const returnType = merger.addTypeToGlobal(contributedSignature.signature.returnType);

            signatures[overloadId] = ExternalImplementation.is(implementation)
                ? {
                      parameters,
                      returnType,
                      external: {
                          ...implementation,
                          contribution: resolvedFunction.contributionId,
                          session: resolvedFunction.sessionName
                      }
                  }
                : {
                      parameters,
                      returnType,
                      body: merger.remapBody(implementation, resolvedFunction.types)
                  };
        }

        functions.push({
            name: functionName,
            signatures
        });
    }

    const classes: TypedPluginClass[] = resolvedPlugins.classes.map((contributed) => ({
        contribution: contributed.contributionId,
        name: contributed.name,
        kind: contributed.declaration.kind,
        fields:
            contributed.declaration.kind === "record"
                ? contributed.declaration.fields.map((field) => ({
                      name: field.name,
                      type: merger.addTypeToGlobal(field.type),
                      ...(field.defaultValue != undefined && {
                          defaultValue: merger.remapContributedExpression(field.defaultValue, contributed.types)
                      })
                  }))
                : []
    }));

    return {
        types: merger.getGlobalTypes(),
        functions,
        classes
    };
}
