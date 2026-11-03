import { sharedImport } from "@mdeo/language-shared";
import type { AstNodeDescription, LangiumCoreServices, LinkingError, ReferenceInfo } from "langium";
import {
    getCallee,
    CallArgument,
    CallOutput,
    CallStatement,
    type CallStatementType
} from "../grammar/modelTransformationTypes.js";

const { DefaultLinker } = sharedImport("langium");

/**
 * Linker of the Model Transformation language.
 *
 * It explains the references of calls that do not resolve: a callee that is not imported, and
 * an argument or output that names no `in` / `out` declaration of the callee.
 */
export class ModelTransformationLinker extends DefaultLinker {
    constructor(services: LangiumCoreServices) {
        super(services);
    }

    override createLinkingError(refInfo: ReferenceInfo, targetDescription?: AstNodeDescription): LinkingError {
        const error = super.createLinkingError(refInfo, targetDescription);
        const name = refInfo.reference.$refText;
        const container = refInfo.container;

        if (refInfo.property === "callee" && this.reflection.isInstance(container, CallStatement.name)) {
            return {
                ...error,
                message: `Unknown transformation '${name}'. Import it first, e.g. import "./${name}.mt" as ${name}.`
            };
        }
        if (
            refInfo.property === "parameter" &&
            (this.reflection.isInstance(container, CallArgument.name) ||
                this.reflection.isInstance(container, CallOutput.name))
        ) {
            const call = container.$container as CallStatementType | undefined;
            const callee = getCallee(call);
            if (callee != undefined) {
                const parameters = (callee.parameters ?? []).map((p) => `${p.direction} ${p.name}`).join(", ");
                return {
                    ...error,
                    message: `'${name}' is not a parameter of '${call?.callee?.$refText}'${parameters.length > 0 ? ` (its parameters: ${parameters})` : " (it has no parameters)"}.`
                };
            }
        }
        return error;
    }
}
