import Fastify, { type FastifyInstance, type FastifyRequest, type FastifyReply } from "fastify";
import cors from "@fastify/cors";
import fastifyStatic from "@fastify/static";
import { resolve } from "path";
import type { ServiceConfig, FileDataComputeRequest, FileDataComputeResponse, LanguageServiceConfig } from "./types.js";
import { LangiumInstancePool } from "../langium/langiumPool.js";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import { URI } from "vscode-uri";
import { buildManifest } from "./util.js";
import type { FileInfo } from "../handler/types.js";
import type { ExecutionContext, ExecutionMetadata, ExecutionRequestContext } from "../execution/types.js";
import { JwtAuthMiddleware } from "../auth/jwtAuth.js";

/**
 * Internal structure for managing a language's pool and configuration.
 */
interface LanguageHandler<T> {
    config: LanguageServiceConfig<T>;
    pool: LangiumInstancePool<T>;
}

/**
 * Extracts JWT token from the Authorization header of a request.
 * Assumes the request has already passed authentication middleware.
 *
 * @param request The Fastify request object
 * @returns The JWT token string
 * @throws Error if Authorization header is missing or malformed
 */
function extractJwtFromRequest(request: FastifyRequest): string {
    const authHeader = request.headers.authorization;
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        throw new Error("Missing or invalid Authorization header");
    }
    return authHeader.substring(7);
}

/**
 * Extracts optional execution metadata from backend-forwarded header.
 */
function extractExecutionMetadataFromRequest(request: FastifyRequest): ExecutionMetadata | undefined {
    const headerValue = request.headers["x-execution-metadata"];
    const raw = Array.isArray(headerValue) ? headerValue[0] : headerValue;
    if (typeof raw !== "string" || raw.trim().length === 0) {
        return undefined;
    }

    try {
        const parsed = JSON.parse(raw) as unknown;
        if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
            return parsed as ExecutionMetadata;
        }
    } catch {
        // Ignore malformed metadata header and proceed without metadata.
    }

    return undefined;
}

/**
 * Creates and configures a Fastify-based language service supporting multiple languages.
 *
 * @param config The service configuration including plugin definition and language handlers
 * @returns Promise resolving to a configured Fastify instance ready to be started
 */
export async function createLanguageService<T>(config: ServiceConfig<T>): Promise<FastifyInstance> {
    const fastify = Fastify({
        logger: {
            transport: {
                target: "pino-pretty"
            },
            level: "warn"
        }
    });

    await fastify.register(cors, {
        origin: true
    });

    if (config.serveStatic !== false) {
        const staticPath = config.staticPath ?? resolve(process.cwd(), "static");
        const version = config.version?.trim();
        const staticPrefix = version && version.length > 0 ? `/static/${version}/` : "/static/";
        await fastify.register(fastifyStatic, {
            root: staticPath,
            prefix: staticPrefix,
            decorateReply: false,
            setHeaders: (res) => {
                res.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
            }
        });
    }

    const languageHandlers = new Map<string, LanguageHandler<T>>();
    for (const langConfig of config.languages) {
        const languageId = langConfig.languagePlugin.id;
        const pool = new LangiumInstancePool<T>({
            maxInstances: config.maxLangiumInstances ?? 5,
            languagePluginProvider: langConfig.languagePluginProvider,
            serviceModule: langConfig.serviceModule,
            languageId,
            extension: langConfig.languagePlugin.extension ?? "",
            backendUrl: config.backendApiUrl
        });
        languageHandlers.set(languageId, { config: langConfig, pool });
    }

    const jwtAuth = new JwtAuthMiddleware(config.backendApiUrl, config.jwtIssuer);

    const manifest = buildManifest(config.plugin, config.version);

    fastify.get("/", async (request: FastifyRequest, reply: FastifyReply) => {
        return reply.send(manifest);
    });

    /**
     * File data endpoint with languageId in the path.
     * POST /data/:languageId/:key
     */
    fastify.post<{
        Params: { languageId: string; key: string };
        Body: FileDataComputeRequest;
    }>(
        "/data/:languageId/:key",
        {
            preHandler: jwtAuth.authenticate.bind(jwtAuth)
        },
        async (request, reply) => {
            const { languageId, key } = request.params;
            const { project, source, contributionPlugins } = request.body;

            if (!JwtAuthMiddleware.hasScope(request, "file-data:read")) {
                return reply.status(403).send({ error: "Insufficient permissions: file-data:read scope required" });
            }

            const languageHandler = languageHandlers.get(languageId);
            if (!languageHandler) {
                return reply.status(404).send({ error: `Unknown language: ${languageId}` });
            }

            const jwt = extractJwtFromRequest(request);

            const handler = languageHandler.config.fileDataHandlers[key];
            if (handler == undefined) {
                return reply.status(404).send({ error: `No handler registered for key: ${key}` });
            }

            const serverContributionPlugins = (contributionPlugins ?? []) as unknown as ServerContributionPlugin[];
            const instance = await languageHandler.pool.acquire(serverContributionPlugins, jwt, project);

            let fileInfo: FileInfo | undefined = undefined;
            if (source != undefined) {
                const uri = URI.parse(source.path);
                fileInfo = {
                    uri,
                    version: source.version
                };
                instance.services.shared.workspace.LangiumDocuments.createDocument(uri, source.content);
            }

            try {
                const result = await handler({
                    fileInfo,
                    instance,
                    services: instance.services,
                    serverApi: instance.services.shared.ServerApi,
                    contributionPlugins: serverContributionPlugins
                });

                const response: FileDataComputeResponse = {
                    ...result,
                    additionalFileData: result.additionalFileData ?? []
                };

                return reply.send(response);
            } finally {
                languageHandler.pool.release(instance);
            }
        }
    );

    const hasRequestHandlers = config.languages.some(
        (lang) => lang.requestHandlers && Object.keys(lang.requestHandlers).length > 0
    );

    if (hasRequestHandlers) {
        fastify.post<{
            Params: { languageId: string; key: string };
            Body: { project: string; body: unknown; contributionPlugins?: object[] };
        }>(
            "/request/:languageId/:key",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, key } = request.params;
                const { project, body, contributionPlugins } = request.body;

                if (!JwtAuthMiddleware.hasScope(request, "file-data:read")) {
                    return reply.status(403).send({ error: "Insufficient permissions: file-data:read scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);

                const handler = languageHandler.config.requestHandlers?.[key];
                if (handler == undefined) {
                    return reply.status(404).send({ error: `No request handler registered for key: ${key}` });
                }

                const serverContributionPlugins = (contributionPlugins ?? []) as unknown as ServerContributionPlugin[];
                const instance = await languageHandler.pool.acquire(serverContributionPlugins, jwt, project);

                try {
                    const result = await handler({
                        body,
                        jwt,
                        instance,
                        services: instance.services,
                        serverApi: instance.services.shared.ServerApi,
                        contributionPlugins: serverContributionPlugins
                    });

                    return reply.send({ data: result ?? null });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );
    }

    const hasExecutionHandlers = config.languages.some(
        (lang) => lang.executionHandlers && lang.executionHandlers.length > 0
    );

    if (hasExecutionHandlers) {
        /**
         * Creates a new execution for a specific language.
         * POST /:languageId/executions
         * Body: { executionId, project, filePath, data }
         */
        fastify.post<{
            Params: { languageId: string };
            Body: {
                executionId: string;
                project: string;
                filePath: string;
                fileContent: string;
                fileVersion: number;
                data: object;
                contributionPlugins?: object[];
            };
        }>(
            "/:languageId/executions",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId } = request.params;
                const { executionId, project, filePath, fileContent, fileVersion, data, contributionPlugins } =
                    request.body;

                if (!JwtAuthMiddleware.hasScope(request, "execution:write")) {
                    return reply
                        .status(403)
                        .send({ error: "Insufficient permissions: execution:write scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply.status(503).send({ error: "Execution service not available for this language" });
                }

                const serverContributionPlugins = (contributionPlugins ?? []) as unknown as ServerContributionPlugin[];
                const instance = await languageHandler.pool.acquire(serverContributionPlugins, jwt, project);

                const uri = URI.parse(filePath);
                instance.services.shared.workspace.LangiumDocuments.createDocument(uri, fileContent);

                const executionContext: ExecutionContext = {
                    executionId,
                    project,
                    filePath,
                    fileContent,
                    fileVersion,
                    data: data ?? {},
                    jwt,
                    contributionPlugins: contributionPlugins ?? [],
                    instance,
                    serverApi: instance.services.shared.ServerApi
                };

                try {
                    let selectedHandler = null;
                    for (const handler of languageHandler.config.executionHandlers) {
                        const canHandleResult = await handler.canHandle(executionContext);
                        if (canHandleResult.canHandle) {
                            selectedHandler = handler;
                            break;
                        }
                    }

                    if (!selectedHandler) {
                        return reply.status(400).send({
                            error: "No handler available for this execution request"
                        });
                    }

                    const result = await selectedHandler.execute(executionContext);
                    return reply.send(result);
                } catch (error) {
                    fastify.log.error(error);
                    return reply.status(500).send({
                        error: error instanceof Error ? error.message : "Execution failed"
                    });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );

        /**
         * Gets the summary for an execution.
         * GET /:languageId/executions/:executionId/summary
         */
        fastify.get<{
            Params: { languageId: string; executionId: string };
        }>(
            "/:languageId/executions/:executionId/summary",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, executionId } = request.params;

                if (!JwtAuthMiddleware.hasScope(request, "plugin:execution:read")) {
                    return reply
                        .status(403)
                        .send({ error: "Insufficient permissions: plugin:execution:read scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send({ error: "JWT does not contain projectId claim" });
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply.status(503).send({ error: "Execution service not available for this language" });
                }

                const handler = languageHandler.config.executionHandlers[0];
                const instance = await languageHandler.pool.acquire([], jwt, project);

                const context: ExecutionRequestContext = {
                    executionId,
                    project,
                    jwt,
                    metadata,
                    instance,
                    serverApi: instance.services.shared.ServerApi
                };

                try {
                    const summary = await handler.getSummary(context);
                    return reply.send({ summary });
                } catch (error) {
                    fastify.log.error(error);
                    return reply.status(500).send({
                        error: error instanceof Error ? error.message : "Failed to get summary"
                    });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );

        /**
         * Gets the file tree for an execution.
         * GET /:languageId/executions/:executionId/files
         */
        fastify.get<{
            Params: { languageId: string; executionId: string };
        }>(
            "/:languageId/executions/:executionId/files",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, executionId } = request.params;

                if (!JwtAuthMiddleware.hasScope(request, "plugin:execution:read")) {
                    return reply
                        .status(403)
                        .send({ error: "Insufficient permissions: plugin:execution:read scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send({ error: "JWT does not contain projectId claim" });
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply.status(503).send({ error: "Execution service not available for this language" });
                }

                const handler = languageHandler.config.executionHandlers[0];
                const instance = await languageHandler.pool.acquire([], jwt, project);

                const context: ExecutionRequestContext = {
                    executionId,
                    project,
                    jwt,
                    metadata,
                    instance,
                    serverApi: instance.services.shared.ServerApi
                };

                try {
                    const files = await handler.getFileTree(context);
                    return reply.send({ files });
                } catch (error) {
                    fastify.log.error(error);
                    return reply.status(500).send({
                        error: error instanceof Error ? error.message : "Failed to get file tree"
                    });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );

        /**
         * Gets a specific file from an execution.
         * GET /:languageId/executions/:executionId/files/:path
         */
        fastify.get<{
            Params: { languageId: string; executionId: string; "*": string };
        }>(
            "/:languageId/executions/:executionId/files/*",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, executionId } = request.params;
                const path = request.params["*"];

                if (!JwtAuthMiddleware.hasScope(request, "plugin:execution:read")) {
                    return reply
                        .status(403)
                        .send({ error: "Insufficient permissions: plugin:execution:read scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send({ error: "JWT does not contain projectId claim" });
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply.status(503).send({ error: "Execution service not available for this language" });
                }

                const handler = languageHandler.config.executionHandlers[0];
                const instance = await languageHandler.pool.acquire([], jwt, project);

                const context: ExecutionRequestContext = {
                    executionId,
                    project,
                    jwt,
                    metadata,
                    instance,
                    serverApi: instance.services.shared.ServerApi
                };

                try {
                    const fileContent = await handler.getFile(context, path);
                    return reply.type("application/octet-stream").send(fileContent);
                } catch (error) {
                    fastify.log.error(error);
                    return reply.status(500).send({
                        error: error instanceof Error ? error.message : "Failed to get file"
                    });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );

        /**
         * Cancels an execution.
         * POST /:languageId/executions/:executionId/cancel
         */
        fastify.post<{
            Params: { languageId: string; executionId: string };
        }>(
            "/:languageId/executions/:executionId/cancel",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, executionId } = request.params;

                if (!JwtAuthMiddleware.hasScope(request, "plugin:execution:cancel")) {
                    return reply
                        .status(403)
                        .send({ error: "Insufficient permissions: plugin:execution:cancel scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send({ error: "JWT does not contain projectId claim" });
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply.status(503).send({ error: "Execution service not available for this language" });
                }

                const handler = languageHandler.config.executionHandlers[0];
                const instance = await languageHandler.pool.acquire([], jwt, project);

                const context: ExecutionRequestContext = {
                    executionId,
                    project,
                    jwt,
                    metadata,
                    instance,
                    serverApi: instance.services.shared.ServerApi
                };

                try {
                    await handler.cancel(context);
                    return reply.status(204).send();
                } catch (error) {
                    fastify.log.error(error);
                    return reply.status(500).send({
                        error: error instanceof Error ? error.message : "Failed to cancel execution"
                    });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );

        /**
         * Deletes an execution.
         * DELETE /:languageId/executions/:executionId
         */
        fastify.delete<{
            Params: { languageId: string; executionId: string };
        }>(
            "/:languageId/executions/:executionId",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, executionId } = request.params;

                if (!JwtAuthMiddleware.hasScope(request, "plugin:execution:delete")) {
                    return reply
                        .status(403)
                        .send({ error: "Insufficient permissions: plugin:execution:write scope required" });
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send({ error: `Unknown language: ${languageId}` });
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send({ error: "JWT does not contain projectId claim" });
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply.status(503).send({ error: "Execution service not available for this language" });
                }

                const handler = languageHandler.config.executionHandlers[0];
                const instance = await languageHandler.pool.acquire([], jwt, project);

                const context: ExecutionRequestContext = {
                    executionId,
                    project,
                    jwt,
                    metadata,
                    instance,
                    serverApi: instance.services.shared.ServerApi
                };

                try {
                    await handler.delete(context);
                    return reply.status(204).send();
                } catch (error) {
                    fastify.log.error(error);
                    return reply.status(500).send({
                        error: error instanceof Error ? error.message : "Failed to delete execution"
                    });
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );
    }

    return fastify;
}

/**
 * Starts a language service with the given configuration.
 * The service will listen on the configured host and port.
 *
 * @param config The service configuration
 * @returns Promise that resolves when the service has started
 */
export async function startLanguageService<T>(config: ServiceConfig<T>): Promise<void> {
    const fastify = await createLanguageService(config);

    try {
        const host = config.host ?? "0.0.0.0";
        await fastify.listen({ port: config.port, host });
        // eslint-disable-next-line no-console
        console.log(`Language service started on ${host}:${config.port}`);
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
}
