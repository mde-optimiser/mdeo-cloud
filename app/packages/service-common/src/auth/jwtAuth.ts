import { errorResponse } from "@mdeo/plugin";
import type { FastifyRequest, FastifyReply } from "fastify";
import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";

/**
 * JWT claims expected in the token payload
 */
export interface JwtClaims extends JWTPayload {
    /**
     * Project ID from the JWT
     */
    projectId?: string;

    /**
     * Execution ID from the JWT
     */
    executionId?: string;

    /**
     * Scopes/permissions granted by the token
     */
    scope?: string[];

    /**
     * Plugin target a session token was issued for, as the `<kind>:<id>` address the caller used.
     */
    target?: string;

    /**
     * Session name a session token was issued for.
     */
    session?: string;
}

/**
 * Authentication middleware that validates JWT tokens using the backend's JWKS endpoint.
 * This middleware extracts the JWT from the Authorization header, validates it using
 * the backend's public keys, and attaches the decoded payload to the request.
 */
export class JwtAuthMiddleware {
    private jwks: ReturnType<typeof createRemoteJWKSet>;
    private issuer: string;

    /**
     * Creates a new JWT authentication middleware.
     *
     * @param backendUrl Base URL of the backend (e.g., "http://localhost:8080/api")
     * @param issuer Expected JWT issuer
     */
    constructor(backendUrl: string, issuer: string) {
        this.jwks = createRemoteJWKSet(new URL(`${backendUrl}/.well-known/jwks.json`));
        this.issuer = issuer;
    }

    /**
     * Fastify hook that validates JWT tokens.
     * This can be used as a preHandler hook for routes that need authentication.
     *
     * @param request Fastify request object
     * @param reply Fastify reply object
     * @throws Will send 401 response if authentication fails
     */
    async authenticate(request: FastifyRequest, reply: FastifyReply): Promise<void> {
        try {
            const authHeader = request.headers.authorization;

            if (!authHeader) {
                return reply.status(401).send(errorResponse(401, "Missing Authorization header"));
            }

            if (!authHeader.startsWith("Bearer ")) {
                return reply
                    .status(401)
                    .send(errorResponse(401, "Invalid Authorization header format. Expected 'Bearer <token>'"));
            }

            const token = authHeader.substring(7);

            const { payload } = await jwtVerify<JwtClaims>(token, this.jwks, {
                issuer: this.issuer,
                algorithms: ["RS256"]
            });

            (request as any).jwtClaims = payload;
        } catch (error) {
            if (error instanceof Error) {
                request.log.warn(`JWT verification failed: ${error.message}`);
            } else {
                request.log.warn(`JWT verification failed with unknown error`);
            }
            return reply.status(401).send(errorResponse(401, "Authentication failed"));
        }
    }

    /**
     * Verifies a bearer token outside of the request pipeline.
     *
     * The HTTP routes get their verification from {@link authenticate}, which only applies to
     * a request that carries an Authorization header. A WebSocket connection is authorized
     * once at the handshake and then carries many requests for potentially different work, so
     * the connection's own credentials say nothing about any individual request on it. This
     * verifies the token each request supplies for itself, against the same JWKS and issuer.
     *
     * @param token The raw bearer token
     * @returns The verified claims
     * @throws If the token is missing, malformed, expired, or not issued by the backend
     */
    async verifyToken(token: string | null | undefined): Promise<JwtClaims> {
        if (!token) {
            throw new Error("Missing token");
        }
        const { payload } = await jwtVerify<JwtClaims>(token, this.jwks, {
            issuer: this.issuer,
            algorithms: ["RS256"]
        });
        return payload;
    }

    /**
     * Helper method to get JWT claims from an authenticated request.
     * This should only be called in routes protected by the authenticate middleware.
     *
     * @param request Fastify request object
     * @returns The JWT claims, or undefined if not authenticated
     */
    static getClaims(request: FastifyRequest): JwtClaims | undefined {
        return (request as any).jwtClaims;
    }

    /**
     * Checks if the JWT token has a specific scope/permission.
     *
     * @param request Fastify request object
     * @param scope The required scope to check for
     * @returns true if the token has the scope, false otherwise
     *
     * @example
     * ```typescript
     * if (JwtAuthMiddleware.hasScope(request, "file-data:read")) {
     *     // User has permission to read file data
     * }
     * ```
     */
    static hasScope(request: FastifyRequest, scope: string): boolean {
        const claims = JwtAuthMiddleware.getClaims(request);
        if (!claims || !claims.scope) {
            return false;
        }
        return claims.scope.includes(scope);
    }
}
