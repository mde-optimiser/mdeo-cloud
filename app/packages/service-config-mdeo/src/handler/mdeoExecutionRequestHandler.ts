import type { RequestHandler, ExecuteResponse } from "@mdeo/service-common";
import type {
    ConfigExecutionPluginRequestBody,
    ConfigExecutionFollowUpRequestBody,
    ConfigExecutionFileRequestBody
} from "@mdeo/service-config-common";
import type { MdeoServices } from "@mdeo/language-config-mdeo";
import type { ClassMutationData, EdgeMutationData, MutationsBlockData } from "./mdeoRequestTypes.js";

/**
 * URL of the optimizer-execution backend service.
 * Configurable via OPTIMIZER_EXECUTION_SERVICE_URL env var.
 */
const OPTIMIZER_SERVICE_URL = process.env.OPTIMIZER_EXECUTION_SERVICE_URL ?? "http://localhost:8083";

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
function buildMutationsPayload(
    mutations: MutationsBlockData
): { usingPaths: string[]; generate: MutationRuleSpec[] } {
    return {
        usingPaths: mutations.usingPaths,
        generate: buildGenerateSpecs(mutations.classMutations, mutations.edgeMutations)
    };
}

/**
 * Execution request handler for MDEO config sections.
 *
 * Fetches the pre-computed config file data and composes the four sections
 * (problem, goal from the optimization plugin; search, solver from the MDEO plugin)
 * into the OptimizationConfig payload expected by the optimizer-execution backend.
 *
 * The search.mutations block is transformed from the frontend shape
 * (usingPaths + classMutations + edgeMutations) into the Kotlin MutationsConfig shape
 * (usingPaths + generate).
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

    const rawSearch = mdeoData.search as { mutations?: MutationsBlockData };
    const transformedSearch = rawSearch?.mutations
        ? { mutations: buildMutationsPayload(rawSearch.mutations) }
        : rawSearch;

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
    const body = context.body as Partial<ConfigExecutionFollowUpRequestBody>;
    const response = await fetch(`${OPTIMIZER_SERVICE_URL}/api/executions/${body.executionId}/summary`, {
        headers: buildHeaders(context.jwt)
    });
    if (!response.ok) {
        throw new Error(`Failed to get optimizer execution summary: ${response.status}`);
    }
    const result = (await response.json()) as { summary?: string };
    return result.summary ?? "";
};

/**
 * File tree request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionGetFileTreeRequestHandler: RequestHandler<unknown[], MdeoServices> = async (context) => {
    const body = context.body as Partial<ConfigExecutionFollowUpRequestBody>;
    const response = await fetch(`${OPTIMIZER_SERVICE_URL}/api/executions/${body.executionId}/file-tree`, {
        headers: buildHeaders(context.jwt)
    });
    if (!response.ok) {
        throw new Error(`Failed to get optimizer execution file tree: ${response.status}`);
    }
    const result = (await response.json()) as { files?: unknown[] };
    return result.files ?? [];
};

/**
 * File read request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionGetFileRequestHandler: RequestHandler<string, MdeoServices> = async (context) => {
    const body = context.body as Partial<ConfigExecutionFileRequestBody>;
    const filePath = body.path ?? "";
    const response = await fetch(`${OPTIMIZER_SERVICE_URL}/api/executions/${body.executionId}/files/${filePath}`, {
        headers: buildHeaders(context.jwt)
    });
    if (!response.ok) {
        throw new Error(`Failed to get optimizer execution file: ${response.status}`);
    }
    return await response.text();
};

/**
 * Cancel request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionCancelRequestHandler: RequestHandler<void, MdeoServices> = async (context) => {
    const body = context.body as Partial<ConfigExecutionFollowUpRequestBody>;
    const response = await fetch(`${OPTIMIZER_SERVICE_URL}/api/executions/${body.executionId}/cancel`, {
        method: "POST",
        headers: buildHeaders(context.jwt)
    });
    if (!response.ok) {
        throw new Error(`Failed to cancel optimizer execution: ${response.status}`);
    }
};

/**
 * Delete request handler — forwards to optimizer-execution backend.
 */
export const mdeoExecutionDeleteRequestHandler: RequestHandler<void, MdeoServices> = async (context) => {
    const body = context.body as Partial<ConfigExecutionFollowUpRequestBody>;
    const response = await fetch(`${OPTIMIZER_SERVICE_URL}/api/executions/${body.executionId}`, {
        method: "DELETE",
        headers: buildHeaders(context.jwt)
    });
    if (!response.ok) {
        throw new Error(`Failed to delete optimizer execution: ${response.status}`);
    }
};
