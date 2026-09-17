import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { errorCodeFor, errorResponse, ErrorCodes } from "../dist/apiError.js";

describe("error responses", () => {
    it("maps statuses to the general codes the Kotlin side uses", () => {
        const expected: Record<number, string> = {
            400: ErrorCodes.BadRequest,
            401: ErrorCodes.Unauthenticated,
            403: ErrorCodes.Forbidden,
            404: ErrorCodes.NotFound,
            409: ErrorCodes.Conflict,
            422: ErrorCodes.BadRequest,
            500: ErrorCodes.Internal,
            502: ErrorCodes.Unavailable,
            503: ErrorCodes.Unavailable,
            504: ErrorCodes.DeadlineExceeded
        };
        for (const [status, code] of Object.entries(expected)) {
            assert.equal(errorCodeFor(Number(status)), code, `status ${status}`);
        }
    });

    it("builds the one error shape, with an explicit code when given", () => {
        assert.deepEqual(errorResponse(404, "gone"), { error: { code: "NotFound", message: "gone" } });
        assert.deepEqual(errorResponse(404, "gone", "FileNotFound"), {
            error: { code: "FileNotFound", message: "gone" }
        });
    });
});
