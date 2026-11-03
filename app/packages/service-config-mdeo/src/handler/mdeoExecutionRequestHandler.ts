import { ExecutionServiceWsProxy, type RequestHandler, type ExecuteResponse } from "@mdeo/service-common";
import type {
    ConfigExecutionPluginRequestBody,
    ConfigExecutionFollowUpRequestBody,
    ConfigExecutionFileRequestBody,
    ConfigExecutionFilesRequestBody,
    ConfigExecutionFilesResponse
} from "@mdeo/service-config-common";
import type { MdeoServices } from "@mdeo/language-config-mdeo";
import type {
    ClassMutationData,
    EdgeMutationData,
    MutationsBlockData,
    OperatorRefData,
    SearchSectionData
} from "./mdeoRequestTypes.js";

/**
 * URL of the optimizer-execution backend service.
 * Configurable via OPTIMIZER_EXECUTION_SERVICE_URL env var.
 */
const OPTIMIZER_SERVICE_URL = process.env.OPTIMIZER_EXECUTION_SERVICE_URL ?? "http://localhost:8083";

/**
 * Result access to the optimizer-execution service over the shared execution WebSocket
 * protocol.
 *
 * An optimization run's results are read through two forwarding hops before they reach the
 * browser, so a request per file was the dominant cost of opening one. This keeps a
 * connection to the optimizer service warm across a burst of reads and can fetch a whole
 * result set in one request.
 */
const optimizerResults = new ExecutionServiceWsProxy(OPTIMIZER_SERVICE_URL);

/**
 * The key used by the config file-data handler (matches CONFIG_DATA_KEY in service-config).
 */
const CONFIG_DATA_KEY = "config";

/** Plugin short names for the two contribution plugins that make up a full config. */
const OPTIMIZATION_PLUGIN_NAME = "optimization";
const MDEO_PLUGIN_NAME = "mdeo";

/**
 * Builds the Authorization header for requests to the optimizer-execution backend.
 */
function buildHeaders(jwt: string): Record<string, string> {
    return {
        "Content-Type": "application/json",
        Authorization: `Bearer ${jwt}`
    };
}

type ConfigFileData = Record<string, Record<string, unknown>>;

/** Kotlin MutationAction enum values accepted by the backend. */
type MutationAction = "ALL" | "CREATE" | "DELETE" | "ADD" | "REMOVE";

/** Shape of a single Kotlin MutationRuleSpec entry. */
type MutationRuleSpec = { node: string; edge?: string; action: MutationAction };

/**
 * Converts the frontend `classMutations` and `edgeMutations` lists into
 * `MutationRuleSpec` entries for the Kotlin `MutationsConfig.generate` field.
 *
 * Mapping:
 *   classMutation create  → CREATE
 *   classMutation delete  → DELETE
 *   classMutation mutate  → ALL
 *   edgeMutation  add     → ADD
 *   edgeMutation  remove  → REMOVE
 *   edgeMutation  mutate  → ADD + REMOVE (two specs)
 */
function buildGenerateSpecs(
    classMutations: ClassMutationData[],
    edgeMutations: EdgeMutationData[]
): MutationRuleSpec[] {
    const specs: MutationRuleSpec[] = [];

    for (const cm of classMutations) {
        const action: MutationAction =
            cm.operator === "create" ? "CREATE" : cm.operator === "delete" ? "DELETE" : "ALL";
        specs.push({ node: cm.className, action });
    }

    for (const em of edgeMutations) {
        if (em.operator === "mutate") {
            specs.push({ node: em.className, edge: em.edgeName, action: "ADD" });
            specs.push({ node: em.className, edge: em.edgeName, action: "REMOVE" });
        } else {
            const action: MutationAction = em.operator === "add" ? "ADD" : "REMOVE";
            specs.push({ node: em.className, edge: em.edgeName, action });
        }
    }

    return specs;
}

/**
 * Transforms the frontend MutationsBlockData into the shape expected by the Kotlin
 * MutationsConfig: replaces classMutations/edgeMutations with a `generate` list.
 */
function buildMutationsPayload(mutations: MutationsBlockData): {
    operators: OperatorRefData[];
    generate: MutationRuleSpec[];
} {
    return {
        operators: mutations.operators,
        generate: buildGenerateSpecs(mutations.classMutations, mutations.edgeMutations)
    };
}

/**
 * Transforms the frontend SearchSectionData into the shape of the Kotlin SearchConfig: the
 * mutations block as {@link buildMutationsPayload} converts it, the crossovers block as it is.
 *
 * @param search The search section data of the config plugin request
 * @returns The `search` part of the OptimizationConfig
 */
export function buildSearchConfig(search: SearchSectionData): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    if (search.mutations != undefined) {
        result.mutations = buildMutationsPayload(search.mutations);
    }
    if (search.crossovers != undefined) {
        result.crossovers = search.crossovers;
    }
    return result;
}

/**
 * Execution request handler for MDEO config sections.
 *
 * Fetches the pre-computed config file data and composes the four sections
 * (problem, goal from the optimization plugin; search, solver from the MDEO plugin)
 * into the OptimizationConfig payload expected by the optimizer-execution backend.
 *
 * The search.mutations block is transformed from the frontend shape
 * (operators + classMutations + edgeMutations) into the Kotlin MutationsConfig shape
 * (operators + generate); search.crossovers is passed on as it is.
 */
export const mdeoExecutionRequestHandler: RequestHandler<ExecuteResponse, MdeoServices> = async (context) => {
    const body = context.body as Partial<ConfigExecutionPluginRequestBody>;

    if (!body.filePath) {
        throw new Error("Missing filePath in execution request body");
    }

    const fileDataResult = await context.serverApi.getFileData(body.filePath, CONFIG_DATA_KEY);
    const configData = fileDataResult.data as ConfigFileData | null;

    if (configData == null) {
        throw new Error(`Config file data not available for: ${body.filePath}`);
    }

    const optimizationData = configData[OPTIMIZATION_PLUGIN_NAME];
    const mdeoData = configData[MDEO_PLUGIN_NAME];

    if (!optimizationData?.problem) {
        throw new Error("Missing 'problem' section in config file data");
    }
    if (!optimizationData?.goal) {
        throw new Error("Missing 'goal' section in config file data");
    }
    if (!mdeoData?.search) {
        throw new Error("Missing 'search' section in config file data");
    }
    if (!mdeoData?.solver) {
        throw new Error("Missing 'solver' section in config file data");
    }

    const transformedSearch = buildSearchConfig(mdeoData.search as SearchSectionData);

    const requestBody = {
        executionId: body.executionId,
        project: body.project,
        filePath: body.filePath,
        data: {
            problem: optimizationData.problem,
            goal: optimizationData.goal,
            search: transformedSearch,
            solver: mdeoData.solver,
            runtime: mdeoData.runtime
        }
    };

    const response = await fetch(`${OPTIMIZER_SERVICE_URL}/api/executions`, {
        method: "POST",
        headers: buildHeaders(context.jwt),
        body: JSON.stringify(requestBody)
    });

    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(
            `Optimizer execution backend returned error: ${response.status} ${response.statusText}. ${errorText}`
        );
    }

    const result = (await response.json()) as { name?: string };
    if (!result.name) {
        throw new Error("Optimizer backend did not return an execution name");
    }

    return { name: result.name };
};

/**
 * Summary request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionGetSummaryRequestHandler: RequestHandler<string, MdeoServices> = async (context) => {
    return optimizerResults.getSummary(proxyContext(context));
};

/**
 * File tree request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionGetFileTreeRequestHandler: RequestHandler<unknown[], MdeoServices> = async (context) => {
    return optimizerResults.getFileTree(proxyContext(context));
};

/**
 * File read request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionGetFileRequestHandler: RequestHandler<string, MdeoServices> = async (context) => {
    const body = context.body as Partial<ConfigExecutionFileRequestBody>;
    return optimizerResults.getFile(proxyContext(context), body.path ?? "");
};

/**
 * Bulk file read request handler — fetches a whole result set from the optimizer-execution
 * backend in one request.
 *
 * The files stream back from the optimizer service one at a time, but the plugin-request
 * channel that carries the answer onward cannot stream, so they are collected and returned
 * together.
 */
export const mdeoExecutionGetFilesRequestHandler: RequestHandler<ConfigExecutionFilesResponse, MdeoServices> = async (
    context
) => {
    const body = context.body as Partial<ConfigExecutionFilesRequestBody>;
    const contents: Record<string, string> = {};
    const files = await optimizerResults.getFiles(proxyContext(context), body.paths ?? null, (path, content) => {
        contents[path] = content;
    });
    return { files, contents };
};

/**
 * Cancel request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionCancelRequestHandler: RequestHandler<void, MdeoServices> = async (context) => {
    await optimizerResults.cancel(proxyContext(context));
};

/**
 * Delete request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionDeleteRequestHandler: RequestHandler<void, MdeoServices> = async (context) => {
    await optimizerResults.delete(proxyContext(context));
};

/**
 * Extracts what the optimizer proxy needs from a plugin request.
 *
 * @param context The plugin request context
 * @returns The execution and token the request addresses
 */
function proxyContext(context: { body: unknown; jwt: string }): { executionId: string; jwt: string } {
    const body = context.body as Partial<ConfigExecutionFollowUpRequestBody>;
    if (!body.executionId) {
        throw new Error("Missing executionId in optimizer execution request body");
    }
    return { executionId: body.executionId, jwt: context.jwt };
}
