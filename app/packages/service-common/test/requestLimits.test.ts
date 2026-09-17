import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import type { FastifyReply, FastifyRequest } from "fastify";
import { createRequestLimits, TIMEOUT_HEADER } from "../dist/util/requestLimits.js";

function request(timeoutMs?: string): FastifyRequest {
    return { headers: timeoutMs == undefined ? {} : { [TIMEOUT_HEADER]: timeoutMs } } as unknown as FastifyRequest;
}

function reply(): { reply: FastifyReply; raw: EventEmitter & { writableFinished: boolean } } {
    const raw = Object.assign(new EventEmitter(), { writableFinished: false });
    return { reply: { raw } as unknown as FastifyReply, raw };
}

describe("request limits", () => {
    it("aborts as timed out once the caller's deadline passes", async () => {
        const { reply: r } = reply();
        const limits = createRequestLimits(request("20"), r);
        assert.ok(limits.deadline != undefined);
        await new Promise((resolve) => setTimeout(resolve, 40));
        assert.equal(limits.signal.aborted, true);
        assert.equal(limits.timedOut, true);
        limits.dispose();
    });

    it("aborts without timing out when the caller goes away", () => {
        const { reply: r, raw } = reply();
        const limits = createRequestLimits(request(), r);
        assert.equal(limits.deadline, undefined);
        raw.emit("close");
        assert.equal(limits.signal.aborted, true);
        assert.equal(limits.timedOut, false);
        limits.dispose();
    });

    it("does not abort when the answer was sent before the connection closed", () => {
        const { reply: r, raw } = reply();
        const limits = createRequestLimits(request(), r);
        raw.writableFinished = true;
        raw.emit("close");
        assert.equal(limits.signal.aborted, false);
        limits.dispose();
    });

    it("ignores a deadline that is not a positive number, and stops watching once disposed", () => {
        for (const header of ["0", "-5", "soon"]) {
            const { reply: r, raw } = reply();
            const limits = createRequestLimits(request(header), r);
            assert.equal(limits.deadline, undefined, header);
            limits.dispose();
            assert.equal(raw.listenerCount("close"), 0);
        }
    });
});
