/**
 * The one error shape of the platform.
 *
 * Every failure a service reports, over HTTP or over a WebSocket, is this object: an HTTP error
 * answers `{"error": {"code": …, "message": …}}` ({@link ErrorResponse}), and a WebSocket error
 * message carries it in its `error` field.
 */
export interface ApiError {
    /**
     * One of {@link ErrorCodes}.
     */
    code: string;
    /**
     * Human-readable description.
     */
    message: string;
}

/**
 * Body of every HTTP error response.
 */
export interface ErrorResponse {
    error: ApiError;
}

/**
 * Error codes, mirrored by `ErrorCodes` in the Kotlin `common` module.
 *
 * The first group describes failures any request can have; the others belong to one area.
 */
export const ErrorCodes = {
    /** A service on the way could not be reached. */
    Unavailable: "Unavailable",
    Unknown: "Unknown",
    /** The request is malformed. */
    BadRequest: "BadRequest",
    /** The request carries no valid session or token. */
    Unauthenticated: "Unauthenticated",
    /** The caller is known but may not do this. */
    Forbidden: "Forbidden",
    /** What the request addresses does not exist. */
    NotFound: "NotFound",
    /** The request conflicts with the current state. */
    Conflict: "Conflict",
    /** The service failed while handling the request. */
    Internal: "Internal",
    /** The caller's deadline passed before the answer was ready. */
    DeadlineExceeded: "DeadlineExceeded"
} as const;

/**
 * The error code that describes an HTTP status when nothing more specific is known.
 *
 * @param status The HTTP status of the error response
 * @returns One of the general {@link ErrorCodes}
 */
export function errorCodeFor(status: number): string {
    switch (status) {
        case 400:
            return ErrorCodes.BadRequest;
        case 401:
            return ErrorCodes.Unauthenticated;
        case 403:
            return ErrorCodes.Forbidden;
        case 404:
            return ErrorCodes.NotFound;
        case 409:
            return ErrorCodes.Conflict;
        case 502:
        case 503:
            return ErrorCodes.Unavailable;
        case 504:
            return ErrorCodes.DeadlineExceeded;
        default:
            return status >= 500 ? ErrorCodes.Internal : ErrorCodes.BadRequest;
    }
}

/**
 * Builds the body of an HTTP error response.
 *
 * @param status The HTTP status the body is sent with
 * @param message Human-readable description
 * @param code One of {@link ErrorCodes}; derived from `status` when omitted
 * @returns The error response body
 */
export function errorResponse(status: number, message: string, code: string = errorCodeFor(status)): ErrorResponse {
    return { error: { code, message } };
}
