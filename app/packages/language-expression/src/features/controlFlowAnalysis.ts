import type { TypirLangiumSpecifics } from "typir-langium";
import type { AstNode } from "langium";
import type { AstReflection, Interface } from "@mdeo/language-common";
import type {
    AssignmentStatementType,
    BaseStatementType,
    StatementsScopeType,
    StatementTypes,
    VariableDeclarationStatementType
} from "../grammar/statementTypes.js";
import type { ExpressionTypes, IdentifierExpressionType } from "../grammar/expressionTypes.js";
import type { ExpressionTypirServices } from "../type-system/services.js";

/**
 * What is known about local variables at one point of a body.
 */
interface FlowState {
    /**
     * Whether control can reach this point. Everything counts as assigned where it cannot.
     */
    reachable: boolean;
    /**
     * The declarations assigned on every path to this point.
     */
    assigned: Set<AstNode>;
    /**
     * The declarations assigned on some path to this point.
     */
    maybeAssigned: Set<AstNode>;
}

/**
 * The analysis of one body: a function or lambda body, or any other block that is not the branch
 * or loop body of a statement.
 */
interface BodyFlow {
    /**
     * The state before each statement of the body, including those of nested blocks.
     */
    before: Map<AstNode, FlowState>;
    /**
     * The statements of the body in execution order, including those of nested blocks, but not
     * those of lambdas, which are bodies of their own.
     */
    statements: BaseStatementType[];
    /**
     * Whether control can reach the end of the body.
     */
    completesNormally: boolean;
    /**
     * The problem of each assignment to a `val` that breaks its rules.
     */
    valProblems: Map<AstNode, string>;
}

/**
 * The walk through one body: how deep in loops it currently is, and at which depth each variable
 * of the body was declared.
 */
interface WalkContext {
    flow: BodyFlow;
    loopDepth: number;
    declarationLoopDepth: Map<AstNode, number>;
}

/**
 * What the control flow analysis tells about a body as a whole.
 */
export interface StatementControlFlow {
    /**
     * Reports whether control can reach the end of a body.
     *
     * @param body The body
     * @returns False when every path through the body jumps away, for example by returning
     */
    completesNormally(body: StatementsScopeType): boolean;

    /**
     * The statements of a body in execution order, including those of its nested blocks, but not
     * those of lambdas inside it.
     *
     * @param body The body
     * @returns The statements
     */
    statementsOf(body: StatementsScopeType): readonly BaseStatementType[];
}

/**
 * The control flow analysis of statement bodies, the one place that knows how control moves
 * through statements.
 *
 * It answers every question that depends on it: whether a body can complete without jumping
 * away (so whether all its paths return), whether a variable is definitely assigned before a
 * statement, and whether an assignment to a `val` breaks its rules. A `val` without an initial
 * value must be assigned exactly once before it is read: not inside a loop it was declared
 * outside of, not inside a lambda, and not where it may already be assigned.
 *
 * Each body is analyzed once, in execution order. A lambda body is analyzed on its own, starting
 * from what is known before the statement that creates the lambda.
 *
 * @template Specifics The language-specific Typir-Langium configuration.
 */
export class ControlFlowAnalysis<Specifics extends TypirLangiumSpecifics> implements StatementControlFlow {
    private readonly reflection: AstReflection;
    private readonly bodies = new WeakMap<AstNode, BodyFlow>();

    /**
     * @param typir The Typir services, to resolve the variable an assignment assigns
     * @param statementTypes Type definitions for statement AST nodes
     * @param expressionTypes Type definitions for expression AST nodes
     * @param jumpStatementTypes Statements of the language besides `break` and `continue` that leave
     * a block, such as `return`
     */
    constructor(
        private readonly typir: ExpressionTypirServices<Specifics>,
        private readonly statementTypes: StatementTypes,
        private readonly expressionTypes: ExpressionTypes,
        private readonly jumpStatementTypes: Interface<AstNode>[] = []
    ) {
        this.reflection = typir.langium.LangiumServices.AstReflection;
    }

    /**
     * Reports whether control can reach the end of a body.
     *
     * @param body The body
     * @returns False when every path through the body jumps away, for example by returning
     */
    completesNormally(body: StatementsScopeType): boolean {
        return this.analyze(body).completesNormally;
    }

    /**
     * The statements of a body in execution order, including those of its nested blocks, but not
     * those of lambdas inside it.
     *
     * @param body The body
     * @returns The statements
     */
    statementsOf(body: StatementsScopeType): readonly BaseStatementType[] {
        return this.analyze(body).statements;
    }

    /**
     * Reports whether a variable is assigned on every path to a statement. A statement control
     * cannot reach counts as having everything assigned.
     *
     * @param statement The statement
     * @param declaration The declaration of the variable
     * @returns True when the variable is definitely assigned before the statement
     */
    isAssignedBefore(statement: BaseStatementType, declaration: VariableDeclarationStatementType): boolean {
        const state = this.stateBefore(statement);
        return state == undefined || !state.reachable || state.assigned.has(declaration);
    }

    /**
     * The problem with an assignment to a `val`, if it breaks the rules of one.
     *
     * @param assignment The assignment
     * @returns The error message, or undefined when the assignment is allowed or assigns no `val`
     */
    getValAssignmentProblem(assignment: AssignmentStatementType): string | undefined {
        return this.analyze(this.bodyOf(assignment)).valProblems.get(assignment);
    }

    private stateBefore(statement: BaseStatementType): FlowState | undefined {
        return this.analyze(this.bodyOf(statement)).before.get(statement);
    }

    /**
     * Finds the body a statement belongs to, climbing out of branch and loop bodies.
     */
    private bodyOf(statement: BaseStatementType): StatementsScopeType {
        let block = statement.$container as StatementsScopeType;
        while (!this.isBody(block)) {
            let owner: AstNode = block.$container!;
            if (this.reflection.isInstance(owner, this.statementTypes.elseIfClauseType)) {
                owner = owner.$container!;
            }
            block = owner.$container as StatementsScopeType;
        }
        return block;
    }

    /**
     * Reports whether a block is a body of its own rather than the branch or loop body of a statement.
     */
    private isBody(block: StatementsScopeType): boolean {
        const owner = block.$container;
        return !(
            owner != undefined &&
            (this.reflection.isInstance(owner, this.statementTypes.ifStatementType) ||
                this.reflection.isInstance(owner, this.statementTypes.elseIfClauseType) ||
                this.reflection.isInstance(owner, this.statementTypes.whileStatementType) ||
                this.reflection.isInstance(owner, this.statementTypes.forStatementType))
        );
    }

    private analyze(body: StatementsScopeType): BodyFlow {
        const cached = this.bodies.get(body);
        if (cached != undefined) {
            return cached;
        }
        const flow: BodyFlow = { before: new Map(), statements: [], completesNormally: true, valProblems: new Map() };
        this.bodies.set(body, flow);
        const end = this.walk(body.statements, this.initialState(body), {
            flow,
            loopDepth: 0,
            declarationLoopDepth: new Map()
        });
        flow.completesNormally = end.reachable;
        return flow;
    }

    /**
     * What is known at the start of a body: nothing for a function, and for a lambda what is known
     * before the statement that creates it.
     */
    private initialState(body: StatementsScopeType): FlowState {
        let current = body.$container;
        while (current != undefined) {
            if (this.reflection.isInstance(current, this.statementTypes.baseStatementType)) {
                const outer = this.stateBefore(current as BaseStatementType);
                if (outer != undefined) {
                    return {
                        reachable: true,
                        assigned: new Set(outer.assigned),
                        maybeAssigned: new Set(outer.maybeAssigned)
                    };
                }
            }
            current = current.$container;
        }
        return { reachable: true, assigned: new Set(), maybeAssigned: new Set() };
    }

    /**
     * Follows statements in execution order, recording the state before each one.
     *
     * @returns The state after the statements
     */
    private walk(statements: BaseStatementType[], state: FlowState, context: WalkContext): FlowState {
        for (const statement of statements) {
            context.flow.statements.push(statement);
            context.flow.before.set(statement, copy(state));

            if (this.reflection.isInstance(statement, this.statementTypes.variableDeclarationStatementType)) {
                context.declarationLoopDepth.set(statement, context.loopDepth);
                if (statement.initialValue != undefined) {
                    assign(state, statement);
                }
            } else if (this.reflection.isInstance(statement, this.statementTypes.assignmentStatementType)) {
                const declaration = this.assignedDeclaration(statement);
                if (declaration != undefined) {
                    if (declaration.isReadonly) {
                        const problem = this.checkValAssignment(declaration, state, context);
                        if (problem != undefined) {
                            context.flow.valProblems.set(statement, problem);
                        }
                    }
                    assign(state, declaration);
                }
            } else if (this.reflection.isInstance(statement, this.statementTypes.ifStatementType)) {
                const branches = [statement.thenBlock, ...statement.elseIfs.map((elseIf) => elseIf.thenBlock)];
                if (statement.elseBlock != undefined) {
                    branches.push(statement.elseBlock);
                }
                const ends = branches.map((branch) => this.walk(branch.statements, copy(state), context));
                if (statement.elseBlock == undefined) {
                    ends.push(state);
                }
                state = join(ends);
            } else if (
                this.reflection.isInstance(statement, this.statementTypes.whileStatementType) ||
                this.reflection.isInstance(statement, this.statementTypes.forStatementType)
            ) {
                context.loopDepth++;
                const bodyStart = context.flow.statements.length;
                this.walk(statement.body.statements, copy(state), context);
                context.loopDepth--;
                // The body may run any number of times, including none, and may be left at any point.
                for (const inBody of context.flow.statements.slice(bodyStart)) {
                    if (this.reflection.isInstance(inBody, this.statementTypes.assignmentStatementType)) {
                        const declaration = this.assignedDeclaration(inBody);
                        if (declaration != undefined) {
                            state.maybeAssigned.add(declaration);
                        }
                    }
                }
            } else if (this.isJump(statement)) {
                state.reachable = false;
            }
        }
        return state;
    }

    private checkValAssignment(
        declaration: VariableDeclarationStatementType,
        state: FlowState,
        context: WalkContext
    ): string | undefined {
        if (declaration.initialValue != undefined) {
            return `Val '${declaration.name}' cannot be reassigned.`;
        }
        const declaredAtDepth = context.declarationLoopDepth.get(declaration);
        if (declaredAtDepth == undefined) {
            return `Val '${declaration.name}' cannot be assigned inside a lambda.`;
        }
        if (context.loopDepth > declaredAtDepth) {
            return `Val '${declaration.name}' cannot be assigned inside a loop.`;
        }
        if (state.reachable && state.maybeAssigned.has(declaration)) {
            return `Val '${declaration.name}' may already have been assigned.`;
        }
        return undefined;
    }

    /**
     * The local variable an assignment assigns, if it assigns one.
     */
    private assignedDeclaration(statement: AssignmentStatementType): VariableDeclarationStatementType | undefined {
        if (!this.reflection.isInstance(statement.left, this.expressionTypes.identifierExpressionType)) {
            return undefined;
        }
        const name = (statement.left as IdentifierExpressionType).name;
        const declaration = this.typir.ScopeProvider.getScope(statement).getEntry(name)?.languageNode;
        if (
            declaration != undefined &&
            this.reflection.isInstance(declaration, this.statementTypes.variableDeclarationStatementType)
        ) {
            return declaration;
        }
        return undefined;
    }

    private isJump(statement: BaseStatementType): boolean {
        return (
            this.reflection.isInstance(statement, this.statementTypes.breakStatementType) ||
            this.reflection.isInstance(statement, this.statementTypes.continueStatementType) ||
            this.jumpStatementTypes.some((type) => this.reflection.isInstance(statement, type))
        );
    }
}

function copy(state: FlowState): FlowState {
    return {
        reachable: state.reachable,
        assigned: new Set(state.assigned),
        maybeAssigned: new Set(state.maybeAssigned)
    };
}

function assign(state: FlowState, declaration: AstNode): void {
    state.assigned.add(declaration);
    state.maybeAssigned.add(declaration);
}

/**
 * Merges the states at the ends of alternative paths. Paths that jump away contribute nothing.
 */
function join(states: FlowState[]): FlowState {
    const reachable = states.filter((state) => state.reachable);
    if (reachable.length === 0) {
        return { reachable: false, assigned: new Set(), maybeAssigned: new Set() };
    }
    return {
        reachable: true,
        assigned: reachable.map((state) => state.assigned).reduce((left, right) => left.intersection(right)),
        maybeAssigned: new Set(reachable.flatMap((state) => [...state.maybeAssigned]))
    };
}
