import Fastify, { type FastifyInstance, type FastifyRequest, type FastifyReply } from "fastify";
import cors from "@fastify/cors";
import compress from "@fastify/compress";
import { COMPRESSION_THRESHOLD_BYTES } from "../util/compression.js";
import { createRequestLimits } from "../util/requestLimits.js";
import {
    CONTRIBUTION_HASH_SUPPORT_HEADER,
    CONTRIBUTIONS_UNKNOWN_HEADER,
    ContributionCache
} from "../util/contributionCache.js";
import fastifyStatic from "@fastify/static";
import { resolve } from "path";
import { createHash } from "node:crypto";
import type { ServiceConfig, FileDataComputeRequest, FileDataComputeResponse, LanguageServiceConfig } from "./types.js";
import { LangiumInstancePool } from "../langium/langiumPool.js";
import { errorResponse, formatPluginTarget, PluginTargetKind, type SessionType } from "@mdeo/plugin";
import { URI } from "vscode-uri";
import { buildManifest } from "./util.js";
import type { FileInfo } from "../handler/types.js";
import type { ExecutionContext, ExecutionMetadata, ExecutionRequestContext } from "../execution/types.js";
import { JwtAuthMiddleware } from "../auth/jwtAuth.js";
import { attachExecutionWebSocketServer } from "../ws/executionWsServer.js";
import { attachSessionServer } from "../ws/sessionServer.js";
import { HttpServerApi } from "./serverApi.js";

/**
 * Default maximum size in bytes of a request body accepted by a language service.
 *
 * Request bodies carry whole source files - the backend posts a file's content when it asks for
 * computed file data, and execution requests carry the file that is being executed - so Fastify's
 * 1 MiB default rejects any file above roughly a megabyte with a 413 before the handler ever runs.
 */
export const DEFAULT_MAX_REQUEST_BODY_BYTES = 64 * 1024 * 1024;

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
        bodyLimit: config.maxRequestBodyBytes ?? DEFAULT_MAX_REQUEST_BODY_BYTES,
        logger: {
            transport: {
                target: "pino-pretty"
            },
            level: process.env.LOG_LEVEL ?? "warn"
        }
    });

    await fastify.register(cors, {
        origin: true
    });

    // ASTs, typed ASTs and model data are JSON that shrinks by an order of magnitude, and the
    // backend and the browser both accept compressed answers.
    await fastify.register(compress, {
        global: true,
        threshold: COMPRESSION_THRESHOLD_BYTES,
        encodings: ["gzip", "deflate"]
    });

    // Failures fastify raises itself (unknown routes, unreadable bodies, uncaught handler errors)
    // answer in the same error shape as the handlers below.
    fastify.setNotFoundHandler((request, reply) => {
        return reply.status(404).send(errorResponse(404, `Route ${request.method} ${request.url} not found`));
    });
    fastify.setErrorHandler((error: { statusCode?: number; message?: string }, request, reply) => {
        const status = error.statusCode != undefined && error.statusCode >= 400 ? error.statusCode : 500;
        if (status >= 500) {
            request.log.error(error);
        }
        return reply.status(status).send(errorResponse(status, error.message ?? "Internal error"));
    });

    if (config.serveStatic !== false) {
        const staticPath = config.staticPath ?? resolve(process.cwd(), "static");
        const version = config.version?.trim();
        const staticPrefix = version && version.length > 0 ? `/static/${version}/` : "/static/";
        await fastify.register(fastifyStatic, {
            root: staticPath,
            prefix: staticPrefix,
            decorateReply: false,
            // @fastify/static v10 hands this callback a FastifyReply rather than the raw
            // ServerResponse, so headers go through reply.header() instead of setHeader().
            setHeaders: (reply) => {
                reply.header("Cross-Origin-Resource-Policy", "cross-origin");
            }
        });
    }

    const languageHandlers = new Map<string, LanguageHandler<T>>();
    for (const langConfig of config.languages) {
        const languageId = langConfig.languagePlugin.id;
        const pool = new LangiumInstancePool<T>({
            maxInstances: config.maxLangiumInstances ?? 5,
            maxSessionInstances: config.maxSessionInstances,
            acquireTimeoutMs: config.langiumAcquireTimeoutMs,
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
    const manifestJson = JSON.stringify(manifest);
    // Sent with every answer, so the backend notices a redeployed plugin and fetches its manifest again.
    const manifestFingerprint = createHash("sha256").update(manifestJson).digest("hex");

    // Contribution sets the backend sent, so later requests can carry just their hash.
    const contributions = new ContributionCache();
    fastify.addHook("onSend", async (_request, reply, payload) => {
        reply.header(CONTRIBUTION_HASH_SUPPORT_HEADER, "1");
        reply.header(MANIFEST_FINGERPRINT_HEADER, manifestFingerprint);
        return payload;
    });

    fastify.get("/", async (request: FastifyRequest, reply: FastifyReply) => {
        return reply.type("application/json").send(manifestJson);
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
            const { project, source, contributionPlugins, contributionHash } = request.body;

            if (!JwtAuthMiddleware.hasScope(request, "file-data:read")) {
                return reply
                    .status(403)
                    .send(errorResponse(403, "Insufficient permissions: file-data:read scope required"));
            }

            const languageHandler = languageHandlers.get(languageId);
            if (!languageHandler) {
                return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
            }

            const jwt = extractJwtFromRequest(request);

            const handler = languageHandler.config.fileDataHandlers[key];
            if (handler == undefined) {
                return reply.status(404).send(errorResponse(404, `No handler registered for key: ${key}`));
            }

            const serverContributionPlugins = contributions.resolve(contributionPlugins, contributionHash);
            if (serverContributionPlugins == undefined) {
                return refuseUnknownContributions(reply);
            }
            const limits = createRequestLimits(request, reply);
            const instance = await languageHandler.pool.acquire(
                serverContributionPlugins,
                jwt,
                project,
                contributionHash
            );
            instance.services.shared.ServerApi.setRequestLimits(limits.signal, limits.deadline);

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
                    contributionPlugins: serverContributionPlugins,
                    signal: limits.signal
                });

                const response: FileDataComputeResponse = {
                    ...result,
                    additionalFileData: result.additionalFileData ?? []
                };

                return reply.send(response);
            } catch (error) {
                if (limits.timedOut) {
                    return reply
                        .status(504)
                        .send(errorResponse(504, `Computing ${key} took longer than the caller could wait`));
                }
                throw error;
            } finally {
                limits.dispose();
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
            Body: { project: string; body: unknown; contributionPlugins?: object[]; contributionHash?: string };
        }>(
            "/request/:languageId/:key",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId, key } = request.params;
                const { project, body, contributionPlugins, contributionHash } = request.body;

                if (!JwtAuthMiddleware.hasScope(request, "file-data:read")) {
                    return reply
                        .status(403)
                        .send(errorResponse(403, "Insufficient permissions: file-data:read scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);

                const handler = languageHandler.config.requestHandlers?.[key];
                if (handler == undefined) {
                    return reply.status(404).send(errorResponse(404, `No request handler registered for key: ${key}`));
                }

                const serverContributionPlugins = contributions.resolve(contributionPlugins, contributionHash);
                if (serverContributionPlugins == undefined) {
                    return refuseUnknownContributions(reply);
                }
                const limits = createRequestLimits(request, reply);
                const instance = await languageHandler.pool.acquire(
                    serverContributionPlugins,
                    jwt,
                    project,
                    contributionHash
                );
                instance.services.shared.ServerApi.setRequestLimits(limits.signal, limits.deadline);

                try {
                    const result = await handler({
                        body,
                        jwt,
                        instance,
                        services: instance.services,
                        serverApi: instance.services.shared.ServerApi,
                        contributionPlugins: serverContributionPlugins,
                        signal: limits.signal
                    });

                    return reply.send({ data: result ?? null });
                } catch (error) {
                    if (limits.timedOut) {
                        return reply
                            .status(504)
                            .send(errorResponse(504, `Request ${key} took longer than the caller could wait`));
                    }
                    throw error;
                } finally {
                    limits.dispose();
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
                contributionHash?: string;
            };
        }>(
            "/:languageId/executions",
            {
                preHandler: jwtAuth.authenticate.bind(jwtAuth)
            },
            async (request, reply) => {
                const { languageId } = request.params;
                const {
                    executionId,
                    project,
                    filePath,
                    fileContent,
                    fileVersion,
                    data,
                    contributionPlugins,
                    contributionHash
                } = request.body;

                if (!JwtAuthMiddleware.hasScope(request, "execution:write")) {
                    return reply
                        .status(403)
                        .send(errorResponse(403, "Insufficient permissions: execution:write scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply
                        .status(503)
                        .send(errorResponse(503, "Execution service not available for this language"));
                }

                const serverContributionPlugins = contributions.resolve(contributionPlugins, contributionHash);
                if (serverContributionPlugins == undefined) {
                    return refuseUnknownContributions(reply);
                }
                const instance = await languageHandler.pool.acquire(
                    serverContributionPlugins,
                    jwt,
                    project,
                    contributionHash
                );

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
                        return reply
                            .status(400)
                            .send(errorResponse(400, "No handler available for this execution request"));
                    }

                    const result = await selectedHandler.execute(executionContext);
                    return reply.send(result);
                } catch (error) {
                    fastify.log.error(error);
                    return reply
                        .status(500)
                        .send(errorResponse(500, error instanceof Error ? error.message : "Execution failed"));
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
                        .send(errorResponse(403, "Insufficient permissions: plugin:execution:read scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send(errorResponse(401, "JWT does not contain projectId claim"));
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply
                        .status(503)
                        .send(errorResponse(503, "Execution service not available for this language"));
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
                    return reply
                        .status(500)
                        .send(errorResponse(500, error instanceof Error ? error.message : "Failed to get summary"));
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
                        .send(errorResponse(403, "Insufficient permissions: plugin:execution:read scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send(errorResponse(401, "JWT does not contain projectId claim"));
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply
                        .status(503)
                        .send(errorResponse(503, "Execution service not available for this language"));
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
                    return reply
                        .status(500)
                        .send(errorResponse(500, error instanceof Error ? error.message : "Failed to get file tree"));
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
                        .send(errorResponse(403, "Insufficient permissions: plugin:execution:read scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send(errorResponse(401, "JWT does not contain projectId claim"));
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply
                        .status(503)
                        .send(errorResponse(503, "Execution service not available for this language"));
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
                    return reply
                        .status(500)
                        .send(errorResponse(500, error instanceof Error ? error.message : "Failed to get file"));
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
                        .send(errorResponse(403, "Insufficient permissions: plugin:execution:cancel scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send(errorResponse(401, "JWT does not contain projectId claim"));
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply
                        .status(503)
                        .send(errorResponse(503, "Execution service not available for this language"));
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
                    return reply
                        .status(500)
                        .send(
                            errorResponse(500, error instanceof Error ? error.message : "Failed to cancel execution")
                        );
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
                        .send(errorResponse(403, "Insufficient permissions: plugin:execution:write scope required"));
                }

                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    return reply.status(404).send(errorResponse(404, `Unknown language: ${languageId}`));
                }

                const jwt = extractJwtFromRequest(request);
                const claims = JwtAuthMiddleware.getClaims(request);
                const project = claims?.projectId;

                if (!project) {
                    return reply.status(401).send(errorResponse(401, "JWT does not contain projectId claim"));
                }

                const metadata = extractExecutionMetadataFromRequest(request);

                if (
                    !languageHandler.config.executionHandlers ||
                    languageHandler.config.executionHandlers.length === 0
                ) {
                    return reply
                        .status(503)
                        .send(errorResponse(503, "Execution service not available for this language"));
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
                    return reply
                        .status(500)
                        .send(
                            errorResponse(500, error instanceof Error ? error.message : "Failed to delete execution")
                        );
                } finally {
                    languageHandler.pool.release(instance);
                }
            }
        );
    }

    // The execution result endpoints above cost the backend one request per file, and the
    // backend is itself one hop in a longer chain. This serves the same operations over a
    // connection the backend keeps between requests, plus a bulk read that returns a whole
    // result set under a single request.
    attachExecutionWebSocketServer(fastify.server, {
        jwtAuth,
        resolveLanguage: (languageId) => {
            const languageHandler = languageHandlers.get(languageId);
            const handler = languageHandler?.config.executionHandlers?.[0];
            if (!languageHandler || !handler) {
                return undefined;
            }
            return {
                handler,
                acquire: (jwt, project) => languageHandler.pool.acquire([], jwt, project),
                release: (instance) => languageHandler.pool.release(instance)
            };
        },
        log: {
            warn: (message) => fastify.log.warn(message),
            error: (message) => fastify.log.error(message)
        }
    });

    const sessionTypes = collectDeclaredSessionTypes(config);
    reportSessionMismatches(config.sessions ?? {}, sessionTypes, (message) => fastify.log.warn(message));

    if (Object.keys(config.sessions ?? {}).length > 0) {
        attachSessionServer(fastify.server, {
            jwtAuth,
            handlers: config.sessions ?? {},
            resolveSessionType: (address, sessionName) => sessionTypes.get(address)?.[sessionName],
            acquireLanguageInstance: async ({ languageId, sessionName }, jwt, project) => {
                const languageHandler = languageHandlers.get(languageId);
                if (!languageHandler) {
                    throw new Error(`Unknown language: ${languageId}`);
                }
                const serverApi = new HttpServerApi(config.backendApiUrl);
                serverApi.setContext(jwt, project);
                const contributionPlugins = await serverApi.getSessionContributionPlugins(languageId, sessionName);
                return languageHandler.pool.acquireForSession(contributionPlugins, jwt, project);
            },
            releaseLanguageInstance: (languageId, instance) => {
                languageHandlers.get(languageId)?.pool.releaseFromSession(instance);
            },
            createServerApi: (jwt, project) => {
                const serverApi = new HttpServerApi(config.backendApiUrl);
                serverApi.setContext(jwt, project);
                return serverApi;
            },
            log: {
                warn: (message) => fastify.log.warn(message),
                error: (message) => fastify.log.error(message)
            }
        });
    }

    return fastify;
}

/**
 * Header every answer of a plugin service carries: a fingerprint of its manifest, by which the
 * backend notices that the plugin was redeployed with a changed manifest.
 */
export const MANIFEST_FINGERPRINT_HEADER = "x-mdeo-manifest-fingerprint";

/**
 * Answers a request that carries only the hash of a contribution set this service does not hold,
 * so the backend sends it again with the payloads.
 *
 * @param reply The reply to send
 * @returns The sent reply
 */
function refuseUnknownContributions(reply: FastifyReply): FastifyReply {
    return reply
        .status(409)
        .header(CONTRIBUTIONS_UNKNOWN_HEADER, "1")
        .send(
            errorResponse(
                409,
                "The contribution plugins of this request are not known to this service; send them again"
            )
        );
}

/**
 * Collects every session type this service's plugin declares, keyed by target address.
 *
 * The manifest is the single place a session type is written down: the connect endpoint hands
 * a caller the protocol and versions from there, and the session endpoint negotiates against
 * the same values. Handlers registered in the service configuration only say what answers.
 *
 * @param config The service configuration
 * @returns Declared session types, keyed by target address and then by session name
 */
function collectDeclaredSessionTypes<T>(config: ServiceConfig<T>): Map<string, Record<string, SessionType>> {
    const declared = new Map<string, Record<string, SessionType>>();

    for (const languagePlugin of config.plugin.languagePlugins) {
        if (languagePlugin.sessions) {
            declared.set(
                formatPluginTarget({ kind: PluginTargetKind.LANGUAGE, id: languagePlugin.id }),
                languagePlugin.sessions
            );
        }
    }

    for (const contribution of config.plugin.contributionPlugins) {
        for (const serverPlugin of contribution.serverContributionPlugins) {
            if (serverPlugin.sessions) {
                declared.set(
                    formatPluginTarget({ kind: PluginTargetKind.CONTRIBUTION, id: serverPlugin.id }),
                    serverPlugin.sessions
                );
            }
        }
    }

    return declared;
}

/**
 * Reports registrations and declarations that do not line up.
 *
 * Either half alone is dead weight: a handler nobody can reach because the manifest advertises
 * no session, or an advertised session that closes every connection because nothing answers it.
 * Both are wiring mistakes worth naming at startup rather than at execution time.
 *
 * @param handlers The handlers the service registered
 * @param declared The session types the manifest advertises
 * @param warn Where to report a mismatch
 */
function reportSessionMismatches(
    handlers: Record<string, Record<string, unknown>>,
    declared: Map<string, Record<string, SessionType>>,
    warn: (message: string) => void
): void {
    for (const [address, sessions] of Object.entries(handlers)) {
        for (const sessionName of Object.keys(sessions)) {
            if (declared.get(address)?.[sessionName] == undefined) {
                warn(
                    `Session handler ${address}/${sessionName} is registered but the plugin ` +
                        `manifest declares no such session, so nothing can connect to it`
                );
            }
        }
    }

    for (const [address, sessions] of declared) {
        for (const sessionName of Object.keys(sessions)) {
            if (handlers[address]?.[sessionName] == undefined) {
                warn(
                    `Session ${address}/${sessionName} is declared in the plugin manifest but ` +
                        `this service registers no handler for it`
                );
            }
        }
    }
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
