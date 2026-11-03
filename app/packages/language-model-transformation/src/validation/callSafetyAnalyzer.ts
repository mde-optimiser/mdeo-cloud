import type { AstNode } from "langium";
import type { AstReflection } from "@mdeo/language-common";
import { sharedImport } from "@mdeo/language-shared";
import {
    getCallee,
    CallStatement,
    ForInStatement,
    ForMatchStatement,
    IfCallStatement,
    IfExpressionStatement,
    IfMatchStatement,
    MatchStatement,
    PatternCollectionAppend,
    PatternLink,
    PatternObjectInstance,
    PatternObjectInstanceDelete,
    PatternObjectInstanceReference,
    PatternVariable,
    PatternVariableReassignment,
    UntilMatchStatement,
    WhileExpressionStatement,
    WhileMatchStatement,
    type BaseTransformationStatementType,
    type CallStatementType,
    type ForInStatementType,
    type IfCallStatementType,
    type IfExpressionStatementType,
    type IfMatchStatementType,
    type ModelTransformationType,
    type PatternLinkType,
    type PatternObjectInstanceReferenceType,
    type PatternObjectInstanceType,
    type PatternType,
    type StatementsScopeType,
    type WhileExpressionStatementType
} from "../grammar/modelTransformationTypes.js";

const { AstUtils } = sharedImport("langium");

/**
 * What the analysis of a transformation found out.
 */
interface TransformationSummary {
    /**
     * Whether the transformation may fail (a `match` that finds nothing, or a call of a
     * transformation that may fail).
     */
    mayFail: boolean;
    /**
     * Whether the transformation may change the model.
     */
    mayModify: boolean;
    /**
     * A description of the first failure that may happen after a change, if there is one.
     */
    violation?: string;
}

/**
 * The state along the paths through a block of statements.
 */
interface PathState {
    /**
     * Where a change may already have happened on some path reaching this point (a description
     * for messages), or undefined when no path has changed the model yet.
     */
    changedAt?: string;
}

/**
 * Decides whether a transformation fails only before it changes the model, the condition under
 * which `if call` may call it.
 *
 * The engine has no rollback, so after a callee that failed half-way the else block of an
 * `if call` would run on a partly changed model. The check is static and conservative: walking
 * the statements in order, every statement that may fail (a `match` whose pattern can find
 * nothing, a call of a transformation that may fail) must come before every statement that may
 * change the model, on every path. Loops count as if their body ran after its own changes, and
 * a transformation called by a plain `call` must itself satisfy the condition when it may fail.
 * `if call` inside the callee does not fail (it takes its else branch), and `kill` is not a
 * failure: it aborts the whole transformation anyway.
 *
 * A pattern cannot fail when it only creates objects and links, deletes or sets properties of
 * bound objects, and declares or assigns variables: it has no object or link to find, no
 * constraint and no condition.
 */
export class CallSafetyAnalyzer {
    /**
     * @param reflection The AST reflection
     */
    constructor(private readonly reflection: AstReflection) {}

    /**
     * Describes the first failure of a transformation that may happen after it changed the model.
     *
     * @param root The transformation
     * @returns A description such as `can fail at line 12 (a match that may find nothing) after
     *   the change at line 8`, or undefined when the transformation fails only before changing
     *   anything
     */
    findUnsafeFailure(root: ModelTransformationType): string | undefined {
        return this.summarize(root, new Map()).violation;
    }

    /**
     * Summarizes a transformation, memoizing by root (calls cannot recurse; a cycle counts as
     * safe and is reported by the import check).
     */
    private summarize(
        root: ModelTransformationType,
        memo: Map<ModelTransformationType, TransformationSummary>
    ): TransformationSummary {
        const known = memo.get(root);
        if (known != undefined) {
            return known;
        }
        const summary: TransformationSummary = { mayFail: false, mayModify: false };
        memo.set(root, summary);
        this.analyzeBlock(root.statements ?? [], {}, summary, memo);
        return summary;
    }

    /**
     * Analyzes a block of statements in order.
     *
     * @returns The state after the block
     */
    private analyzeBlock(
        statements: BaseTransformationStatementType[],
        state: PathState,
        summary: TransformationSummary,
        memo: Map<ModelTransformationType, TransformationSummary>
    ): PathState {
        let current = state;
        for (const statement of statements) {
            current = this.analyzeStatement(statement, current, summary, memo);
        }
        return current;
    }

    /**
     * Analyzes one statement.
     *
     * @returns The state after the statement
     */
    private analyzeStatement(
        statement: BaseTransformationStatementType,
        state: PathState,
        summary: TransformationSummary,
        memo: Map<ModelTransformationType, TransformationSummary>
    ): PathState {
        const block = (scope: StatementsScopeType | undefined, before: PathState) =>
            this.analyzeBlock(scope?.statements ?? [], before, summary, memo);
        const join = (...states: PathState[]): PathState => ({
            changedAt: states.find((s) => s.changedAt != undefined)?.changedAt
        });
        const changedBy = (pattern: PatternType | undefined, before: PathState): PathState =>
            before.changedAt == undefined && this.patternModifies(pattern)
                ? { changedAt: `the change at ${this.lineOf(pattern)}` }
                : before;

        if (this.reflection.isInstance(statement, MatchStatement)) {
            const pattern = (statement as { pattern?: PatternType }).pattern;
            if (this.patternCanFail(pattern)) {
                this.fail(summary, state, `can fail at ${this.lineOf(statement)} (a match that may find nothing)`);
            }
            if (this.patternModifies(pattern)) {
                summary.mayModify = true;
            }
            return changedBy(pattern, state);
        }
        if (this.reflection.isInstance(statement, IfMatchStatement)) {
            const ifMatch = statement as IfMatchStatementType;
            const pattern = ifMatch.ifBlock?.pattern;
            if (this.patternModifies(pattern)) {
                summary.mayModify = true;
            }
            const thenState = block(ifMatch.ifBlock?.thenBlock, changedBy(pattern, state));
            const elseState = block(ifMatch.elseBlock, state);
            return join(thenState, elseState);
        }
        if (
            this.reflection.isInstance(statement, WhileMatchStatement) ||
            this.reflection.isInstance(statement, ForMatchStatement) ||
            this.reflection.isInstance(statement, UntilMatchStatement)
        ) {
            const loop = statement as { pattern?: PatternType; doBlock?: StatementsScopeType };
            if (this.patternModifies(loop.pattern)) {
                summary.mayModify = true;
            }
            const bodyChange = this.blockModifies(loop.doBlock?.statements ?? [], memo)
                ? { changedAt: `the change at ${this.lineOf(loop.doBlock)}` }
                : {};
            // a later iteration runs after the changes of the earlier ones
            const loopState = join(changedBy(loop.pattern, state), bodyChange);
            block(loop.doBlock, loopState);
            return loopState;
        }
        if (this.reflection.isInstance(statement, IfExpressionStatement)) {
            const ifExpression = statement as IfExpressionStatementType;
            return join(
                block(ifExpression.thenBlock, state),
                ...(ifExpression.elseIfBranches ?? []).map((branch) => block(branch.block, state)),
                block(ifExpression.elseBlock, state)
            );
        }
        if (this.reflection.isInstance(statement, WhileExpressionStatement)) {
            const loop = statement as WhileExpressionStatementType;
            const loopState = join(
                state,
                this.blockModifies(loop.block?.statements ?? [], memo)
                    ? { changedAt: `the change at ${this.lineOf(loop.block)}` }
                    : {}
            );
            block(loop.block, loopState);
            return loopState;
        }
        if (this.reflection.isInstance(statement, ForInStatement)) {
            const loop = statement as ForInStatementType;
            const loopState = join(
                state,
                this.blockModifies(loop.doBlock?.statements ?? [], memo)
                    ? { changedAt: `the change at ${this.lineOf(loop.doBlock)}` }
                    : {}
            );
            block(loop.doBlock, loopState);
            return loopState;
        }
        if (this.reflection.isInstance(statement, CallStatement)) {
            return this.analyzeCall(statement as CallStatementType, state, summary, memo);
        }
        if (this.reflection.isInstance(statement, IfCallStatement)) {
            const ifCall = statement as IfCallStatementType;
            const callee = this.calleeSummary(ifCall.ifBlock?.call, memo);
            if (callee?.mayModify === true) {
                summary.mayModify = true;
            }
            const afterCall =
                state.changedAt == undefined && callee?.mayModify === true
                    ? { changedAt: `the call of '${ifCall.ifBlock?.call?.callee?.$refText}' at ${this.lineOf(ifCall)}` }
                    : state;
            return join(block(ifCall.ifBlock?.thenBlock, afterCall), block(ifCall.elseBlock, state));
        }
        // stop, kill and assignments neither fail nor change the model
        return state;
    }

    /**
     * Analyzes a plain call: it fails when the callee fails.
     */
    private analyzeCall(
        call: CallStatementType,
        state: PathState,
        summary: TransformationSummary,
        memo: Map<ModelTransformationType, TransformationSummary>
    ): PathState {
        const callee = this.calleeSummary(call, memo);
        const name = call.callee?.$refText ?? "?";
        if (callee == undefined) {
            return state;
        }
        if (callee.mayFail) {
            this.fail(summary, state, `calls '${name}' at ${this.lineOf(call)}, which can fail,`);
        }
        if (callee.violation != undefined && summary.violation == undefined) {
            summary.mayFail = true;
            summary.violation = `calls '${name}' at ${this.lineOf(call)}, which ${callee.violation}`;
        }
        if (callee.mayModify) {
            summary.mayModify = true;
            if (state.changedAt == undefined) {
                return { changedAt: `the call of '${name}' at ${this.lineOf(call)}` };
            }
        }
        return state;
    }

    /**
     * Summarizes the callee of a call.
     */
    private calleeSummary(
        call: CallStatementType | undefined,
        memo: Map<ModelTransformationType, TransformationSummary>
    ): TransformationSummary | undefined {
        const root = getCallee(call);
        return root != undefined ? this.summarize(root, memo) : undefined;
    }

    /**
     * Records a possible failure; it is a violation when a change may have happened before.
     */
    private fail(summary: TransformationSummary, state: PathState, what: string): void {
        summary.mayFail = true;
        if (state.changedAt != undefined && summary.violation == undefined) {
            summary.violation = `${what} after ${state.changedAt}`;
        }
    }

    /**
     * Whether a block may change the model.
     */
    private blockModifies(
        statements: BaseTransformationStatementType[],
        memo: Map<ModelTransformationType, TransformationSummary>
    ): boolean {
        for (const statement of statements) {
            for (const node of [statement, ...AstUtils.streamAllContents(statement)]) {
                if (
                    (this.reflection.isInstance(node, MatchStatement) ||
                        this.reflection.isInstance(node, WhileMatchStatement) ||
                        this.reflection.isInstance(node, ForMatchStatement) ||
                        this.reflection.isInstance(node, UntilMatchStatement)) &&
                    this.patternModifies((node as { pattern?: PatternType }).pattern)
                ) {
                    return true;
                }
                if (
                    this.reflection.isInstance(node, IfMatchStatement) &&
                    this.patternModifies((node as IfMatchStatementType).ifBlock?.pattern)
                ) {
                    return true;
                }
                if (
                    this.reflection.isInstance(node, CallStatement) &&
                    this.calleeSummary(node as CallStatementType, memo)?.mayModify === true
                ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a pattern may find nothing.
     */
    private patternCanFail(pattern: PatternType | undefined): boolean {
        for (const element of pattern?.elements ?? []) {
            if (
                this.reflection.isInstance(element, PatternVariable) ||
                this.reflection.isInstance(element, PatternVariableReassignment) ||
                this.reflection.isInstance(element, PatternCollectionAppend) ||
                this.reflection.isInstance(element, PatternObjectInstanceDelete)
            ) {
                continue;
            }
            if (this.reflection.isInstance(element, PatternObjectInstance)) {
                const object = element as PatternObjectInstanceType;
                if (object.modifier?.modifier === "create" && this.onlySetsProperties(object)) {
                    continue;
                }
                return true;
            }
            if (this.reflection.isInstance(element, PatternObjectInstanceReference)) {
                if (this.onlySetsProperties(element as PatternObjectInstanceReferenceType)) {
                    continue;
                }
                return true;
            }
            if (this.reflection.isInstance(element, PatternLink)) {
                if ((element as PatternLinkType).modifier?.modifier === "create") {
                    continue;
                }
                return true;
            }
            // where clauses, forbid / require blocks and model scope blocks (which only find)
            return true;
        }
        return false;
    }

    /**
     * Whether an object of a pattern only sets properties (`name = value`) and constrains none.
     */
    private onlySetsProperties(object: { properties?: { operator?: string }[] }): boolean {
        return (object.properties ?? []).every((property) => property.operator === "=");
    }

    /**
     * Whether a pattern changes the model: it creates or deletes objects or links, or sets a
     * property.
     */
    private patternModifies(pattern: PatternType | undefined): boolean {
        for (const element of pattern?.elements ?? []) {
            if (this.reflection.isInstance(element, PatternObjectInstanceDelete)) {
                return true;
            }
            if (
                this.reflection.isInstance(element, PatternObjectInstance) ||
                this.reflection.isInstance(element, PatternLink)
            ) {
                if ((element as { modifier?: { modifier?: string } }).modifier?.modifier != undefined) {
                    return true;
                }
            }
            if (
                this.reflection.isInstance(element, PatternObjectInstance) ||
                this.reflection.isInstance(element, PatternObjectInstanceReference)
            ) {
                if (!this.onlyConstrains(element as { properties?: { operator?: string }[] })) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an object of a pattern sets no property.
     */
    private onlyConstrains(object: { properties?: { operator?: string }[] }): boolean {
        return (object.properties ?? []).every((property) => property.operator !== "=");
    }

    /**
     * Describes the line of a node, with the file name when it is not the file being validated.
     */
    private lineOf(node: AstNode | undefined): string {
        const line = node?.$cstNode != undefined ? node.$cstNode.range.start.line + 1 : undefined;
        if (node == undefined) {
            return "?";
        }
        const file = AstUtils.getDocument(node).uri.path.split("/").pop();
        return line != undefined ? `line ${line} of ${file}` : `${file}`;
    }
}
