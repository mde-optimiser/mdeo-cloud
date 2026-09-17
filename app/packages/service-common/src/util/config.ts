import { DEFAULT_MAX_REQUEST_BODY_BYTES } from "../service/service.js";

/**
 * Reads a whole number from the environment.
 *
 * A value that is not one, or is below the minimum, is a configuration mistake. It is refused at
 * startup rather than read as `NaN`, which would silently disable a limit or make every wait fail.
 *
 * @param name The variable
 * @param fallback The value when the variable is not set
 * @param minimum The smallest accepted value
 * @returns The value
 * @throws Error when the variable is set to something else
 */
function readInteger(name: string, fallback: number, minimum: number): number {
    const raw = process.env[name]?.trim();
    if (raw == undefined || raw.length === 0) {
        return fallback;
    }
    const value = Number(raw);
    if (!Number.isInteger(value) || value < minimum) {
        throw new Error(`${name} must be a whole number of at least ${minimum}, but is '${raw}'`);
    }
    return value;
}

/**
 * Parses configuration from environment variables with defaults.
 *
 * @returns Configuration object with port, host, backendApiUrl, jwtIssuer, the Langium pool
 *   settings (instance count, session budget and acquisition timeout), the session limit and
 *   maxRequestBodyBytes
 */
export function parseServiceConfigFromEnv(): {
    port: number;
    host: string;
    backendApiUrl: string;
    jwtIssuer: string;
    maxLangiumInstances: number;
    maxSessionInstances: number;
    maxSessions: number;
    langiumAcquireTimeoutMs: number;
    maxRequestBodyBytes: number;
    version?: string;
} {
    const port = readInteger("PORT", 3000, 0);
    const host = process.env.HOST ?? "0.0.0.0";
    const backendApiUrl = process.env.BACKEND_API_URL ?? "http://localhost:8080/api";
    const jwtIssuer = process.env.JWT_ISSUER ?? "mdeo-platform";
    const maxLangiumInstances = readInteger("MAX_LANGIUM_INSTANCES", 5, 1);
    const maxSessionInstances = readInteger("MAX_SESSION_INSTANCES", 2, 0);
    const maxSessions = readInteger("MAX_SESSIONS", 64, 1);
    const langiumAcquireTimeoutMs = readInteger("LANGIUM_ACQUIRE_TIMEOUT_MS", 30000, 1);
    const maxRequestBodyBytes = readInteger("MAX_REQUEST_BODY_BYTES", DEFAULT_MAX_REQUEST_BODY_BYTES, 1);
    const version = process.env.SERVICE_VERSION?.trim();

    return {
        port,
        host,
        backendApiUrl,
        jwtIssuer,
        maxLangiumInstances,
        maxSessionInstances,
        maxSessions,
        langiumAcquireTimeoutMs,
        maxRequestBodyBytes,
        version: version && version.length > 0 ? version : undefined
    };
}
