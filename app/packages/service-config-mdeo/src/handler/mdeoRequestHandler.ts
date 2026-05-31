import type { RequestHandler } from "@mdeo/service-common";
import { buildConfigPluginDocument, type ConfigPluginRequestResponse } from "@mdeo/service-config-common";
import { getWrapperInterfaceName } from "@mdeo/language-config";
import { CONFIG_MDEO_LANGUAGE_KEY, MDEO_PLUGIN_NAME } from "@mdeo/language-config-mdeo";
import type {
    SearchSectionType,
    SolverSectionType,
    MutationsBlockType,
    UsingPathType,
    ClassMutationType,
    EdgeMutationType,
    MutationStepValueType,
    MutationBlockType,
    AlgorithmParametersType,
    TerminationBlockType,
    RuntimeSectionType,
    RuntimeTimeoutBlockType,
    RuntimeResourcesBlockType
} from "@mdeo/language-config-mdeo";
import { URI } from "langium";
import type { LangiumDocument } from "langium";
import { resolveRelativePath } from "@mdeo/language-shared";
import type {
    SearchSectionData,
    SolverSectionData,
    MdeoRequestResponse,
    MutationsBlockData,
    ClassMutationData,
    EdgeMutationData,
    MutationStepConfig,
    MutationBlockData,
    AlgorithmParametersData,
    TerminationBlockData,
    RuntimeSectionData,
    RuntimeTimeoutData,
    RuntimeResourcesData
} from "./mdeoRequestTypes.js";
import type { MdeoServices } from "@mdeo/language-config-mdeo";
import type { ServiceMdeoMetamodelResolver } from "../serviceMdeoMetamodelResolver.js";

/**
 * Extracts the absolute path for a using-path node.
 *
 * @param usingPath The using-path AST node.
 * @param document The Langium document used to resolve the relative path.
 * @returns The absolute file system path.
 */
function extractUsingPath(usingPath: UsingPathType, document: LangiumDocument): string {
    const relativePath = usingPath.path ?? "";
    return resolveRelativePath(document, relativePath).path;
}

/**
 * Extracts class mutation data.
 *
 * @param mutation The class mutation node
 * @returns The extracted data
 */
function extractClassMutation(mutation: ClassMutationType): ClassMutationData {
    return {
        operator: mutation.operator as "create" | "delete" | "mutate",
        className: mutation.class?.$refText ?? ""
    };
}

/**
 * Extracts edge mutation data.
 *
 * @param mutation The edge mutation node
 * @returns The extracted data
 */
function extractEdgeMutation(mutation: EdgeMutationType): EdgeMutationData {
    return {
        operator: mutation.operator as "add" | "remove" | "mutate",
        className: mutation.class?.$refText ?? "",
        edgeName: mutation.edge?.$refText ?? ""
    };
}

/**
 * Extracts mutations block data.
 *
 * @param mutations The mutations block AST node.
 * @param document The Langium document for resolving using-path entries.
 * @returns The extracted mutations block data.
 */
function extractMutationsBlock(mutations: MutationsBlockType, document: LangiumDocument): MutationsBlockData {
    return {
        usingPaths: (mutations.usingPaths ?? []).map((up) => extractUsingPath(up as UsingPathType, document)),
        classMutations: (mutations.classMutations ?? []).map((cm) => extractClassMutation(cm as ClassMutationType)),
        edgeMutations: (mutations.edgeMutations ?? []).map((em) => extractEdgeMutation(em as EdgeMutationType))
    };
}

/**
 * Extracts search section data.
 *
 * @param section The search section node
 * @param document The config Langium document
 * @returns The extracted data
 */
function extractSearchData(section: SearchSectionType, document: LangiumDocument): SearchSectionData {
    const mutations = section.mutations[0];
    return {
        mutations: mutations ? extractMutationsBlock(mutations as MutationsBlockType, document) : undefined
    };
}

/**
 * Converts an AST mutation step node to a MutationStepConfig matching
 * the Kotlin sealed class @SerialName discriminators ("Fixed" / "Interval").
 *
 * @param step The mutation step value AST node.
 * @returns The corresponding {@link MutationStepConfig} for serialisation.
 */
function extractMutationStepValue(step: MutationStepValueType): MutationStepConfig {
    const typed = step as unknown as { $type: string; value?: number; n?: number; lower?: number; upper?: number };
    switch (typed.$type) {
        case "ConfigMdeoMutationStepNumeric":
            return { type: "Fixed", n: typed.value ?? 0 };
        case "ConfigMdeoMutationStepFixed":
            return { type: "Fixed", n: 1 };
        case "ConfigMdeoMutationStepFixedN":
            return { type: "Fixed", n: typed.n ?? 0 };
        case "ConfigMdeoMutationStepInterval":
            return { type: "Interval", lower: typed.lower ?? 0, upper: typed.upper ?? 0 };
        default:
            return { type: "Fixed", n: 1 };
    }
}

/**
 * Extracts mutation block data.
 *
 * @param mutation The mutation block AST node.
 * @returns The extracted mutation block data.
 */
function extractMutationBlock(mutation: MutationBlockType): MutationBlockData {
    const step = mutation.step[0];
    const strategy = mutation.strategy[0];
    return {
        step: step ? extractMutationStepValue(step as MutationStepValueType) : undefined,
        strategy: strategy != null ? (strategy.toUpperCase() as MutationBlockData["strategy"]) : undefined
    };
}

/**
 * Extracts algorithm parameters block data.
 *
 * @param params The algorithm parameters AST node.
 * @returns The extracted algorithm parameters data.
 */
function extractAlgorithmParameters(params: AlgorithmParametersType): AlgorithmParametersData {
    const mutation = params.mutation[0];
    const archive = params.archive[0] as unknown as { size?: number[] } | undefined;
    const variation = params.variation[0];
    return {
        population: params.population[0],
        variation: variation != null ? (variation.toUpperCase() as AlgorithmParametersData["variation"]) : undefined,
        mutation: mutation ? extractMutationBlock(mutation as MutationBlockType) : undefined,
        bisections: params.bisections[0],
        archiveSize: archive?.size?.[0]
    };
}

/**
 * Extracts termination block data.
 *
 * @param termination The termination block AST node.
 * @returns The extracted termination block data.
 */
function extractTerminationBlock(termination: TerminationBlockType): TerminationBlockData {
    return {
        evolutions: termination.evolutions[0],
        time: termination.time[0],
        delta: termination.delta[0],
        iterations: termination.iterations[0]
    };
}

/**
 * Extracts solver section data.
 *
 * @param section The solver section AST node.
 * @returns The extracted solver section data.
 */
function extractSolverData(section: SolverSectionType): SolverSectionData {
    const parameters = section.parameters[0];
    const termination = section.termination[0];
    return {
        algorithm: section.algorithm[0] as SolverSectionData["algorithm"],
        parameters: parameters ? extractAlgorithmParameters(parameters as AlgorithmParametersType) : undefined,
        termination: termination ? extractTerminationBlock(termination as TerminationBlockType) : undefined,
        batches: section.batches[0]
    };
}

/**
 * Extracts runtime timeout block data.
 *
 * @param timeout The runtime timeout block AST node.
 * @returns The extracted timeout data.
 */
function extractRuntimeTimeoutBlock(timeout: RuntimeTimeoutBlockType): RuntimeTimeoutData {
    return {
        script: timeout.script[0],
        transformation: timeout.transformation[0]
    };
}

/**
 * Extracts runtime resources block data.
 *
 * @param resources The runtime resources block AST node.
 * @returns The extracted resources data.
 */
function extractRuntimeResourcesBlock(resources: RuntimeResourcesBlockType): RuntimeResourcesData {
    return {
        threads: resources.threads[0],
        nodes: resources.nodes[0],
        threadsPerNode: resources.threadsPerNode[0]
    };
}

/**
 * Extracts runtime section data.
 *
 * @param section The runtime section AST node.
 * @returns The extracted runtime section data.
 */
function extractRuntimeData(section: RuntimeSectionType): RuntimeSectionData {
    const timeout = section.timeout[0];
    const resources = section.resources[0];
    return {
        timeout: timeout ? extractRuntimeTimeoutBlock(timeout as RuntimeTimeoutBlockType) : undefined,
        backend: section.backend[0] as RuntimeSectionData["backend"],
        resources: resources ? extractRuntimeResourcesBlock(resources as RuntimeResourcesBlockType) : undefined
    };
}

/**
 * Request handler for the MDEO contribution plugin.
 * Receives a partial config text (containing only MDEO sections) and
 * parses the text using the MDEO language services, extracting structured data.
 *
 * Always returns a {@link ConfigPluginRequestResponse} so that tracked dependencies
 * are included even when processing fails. The {@link ConfigPluginRequestResponse.data}
 * field is `null` when the text contains lexer or parser errors.
 */
export const mdeoRequestHandler: RequestHandler<
    ConfigPluginRequestResponse<MdeoRequestResponse>,
    MdeoServices
> = async (context): Promise<ConfigPluginRequestResponse<MdeoRequestResponse>> => {
    const resolver = context.services.MdeoMetamodelResolver as ServiceMdeoMetamodelResolver;

    const result = await buildConfigPluginDocument(
        context,
        CONFIG_MDEO_LANGUAGE_KEY,
        (requestBody) => {
            const problemData = (requestBody.dependencyData?.["optimization"]?.["problem"] ?? {}) as {
                metamodelPath?: string;
            };
            if (problemData.metamodelPath) {
                resolver.setMetamodelData(URI.file(problemData.metamodelPath));
            }
        },
        () => resolver.clearMetamodelData()
    );

    if (result.type === "empty") {
        return { data: {}, ...context.serverApi.getTrackedRequests() };
    }
    if (result.type === "error") {
        return { data: null, ...context.serverApi.getTrackedRequests() };
    }

    const { document, sections } = result;
    const response: MdeoRequestResponse = {};

    const searchWrapperType = getWrapperInterfaceName("search", MDEO_PLUGIN_NAME);
    const solverWrapperType = getWrapperInterfaceName("solver", MDEO_PLUGIN_NAME);
    const runtimeWrapperType = getWrapperInterfaceName("runtime", MDEO_PLUGIN_NAME);

    for (const section of sections) {
        if (section.$type === searchWrapperType) {
            response.search = extractSearchData(section.content as SearchSectionType, document);
        } else if (section.$type === solverWrapperType) {
            response.solver = extractSolverData(section.content as SolverSectionType);
        } else if (section.$type === runtimeWrapperType) {
            response.runtime = extractRuntimeData(section.content as RuntimeSectionType);
        }
    }
    return { data: response, ...context.serverApi.getTrackedRequests() };
};
