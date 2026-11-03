import type {
    ActionExecutionRequest,
    ActionStartParams,
    ActionStartResponse,
    ActionSubmitParams,
    ActionSubmitResponse,
    FileMenuActionData,
    ExtendedLangiumSharedServices
} from "@mdeo/language-common";
import { FileCategory, describeValidationErrors, parseUri } from "@mdeo/language-common";
import { sharedImport, type ActionHandler } from "@mdeo/language-shared";

const { URI } = sharedImport("langium");

/**
 * Run action handler for executable MDEO config sections.
 * Produces a direct execution request for the originating .config file.
 */
export class RunMdeoConfigActionHandler implements ActionHandler {
    private readonly sharedServices: ExtendedLangiumSharedServices;

    constructor(sharedServices: ExtendedLangiumSharedServices) {
        this.sharedServices = sharedServices;
    }

    async startAction(params: ActionStartParams): Promise<ActionStartResponse> {
        const typedData = params.data as Partial<FileMenuActionData>;
        if (typeof typedData.uri === "string") {
            const validationError = this.checkValidationErrors(URI.parse(typedData.uri).toString());
            if (validationError != undefined) {
                return {
                    kind: "error",
                    message: "Cannot start execution",
                    description: validationError
                };
            }
        }

        const executionRequest = this.createExecutionRequest(params.data);
        if (executionRequest == undefined) {
            return {
                kind: "error",
                message: "Invalid config file URI for run action"
            };
        }

        return {
            kind: "completion",
            executions: [executionRequest]
        };
    }

    async submitAction(_params: ActionSubmitParams): Promise<ActionSubmitResponse> {
        return {
            kind: "error",
            message: "Run action does not support additional submit steps"
        };
    }

    /**
     * Checks if the given URI and its transitive dependencies have validation errors.
     *
     * @param mainUri The main document URI string to check along with its transitive deps
     * @returns An error message listing files with errors, or undefined if none found
     */
    private checkValidationErrors(mainUri: string): string | undefined {
        const builder = this.sharedServices.workspace.DocumentBuilder;
        const allUris = new Set<string>([mainUri]);
        for (const dep of builder.getTransitiveDependencies(mainUri)) {
            allUris.add(dep);
        }
        const langiumDocuments = this.sharedServices.workspace.LangiumDocuments;
        return describeValidationErrors(
            [...allUris].flatMap((uri) => langiumDocuments.getDocument(URI.parse(uri)) ?? [])
        );
    }

    private createExecutionRequest(data: unknown): ActionExecutionRequest | undefined {
        const typedData = data as Partial<FileMenuActionData>;
        if (typeof typedData.uri !== "string") {
            return undefined;
        }

        const parsedUri = parseUri(URI.parse(typedData.uri));
        if (parsedUri.category !== FileCategory.RegularFile) {
            return undefined;
        }

        return {
            filePath: parsedUri.path,
            data: {}
        };
    }
}
