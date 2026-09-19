import type { TypirLangiumSpecifics } from "typir-langium";
import { BaseScopeProvider } from "../typir-extensions/langium/baseScopeProvider.js";
import { DefaultScope, type BoundScope, type Scope, type ScopeEntry } from "../typir-extensions/scope/scope.js";
import type { ExpressionTypirServices } from "../type-system/services.js";
import type { ForStatementType, StatementsScopeType, StatementTypes } from "../grammar/statementTypes.js";
import type { AstReflection } from "@mdeo/language-common";
import type { TypeInferenceCollector } from "typir";
import type { ExpressionTypes } from "../grammar/expressionTypes.js";
import type { ControlFlowAnalysis } from "./controlFlowAnalysis.js";
import type { ClassType } from "../typir-extensions/config/type.js";

/**
 * Scope provider for statement-based language constructs.
 *
 * Handles scope creation and management for statements including variable declarations,
 * control flow statements (if/else, loops), and for-loop iteration variables. Manages
 * variable visibility. Whether a variable is initialized at a point is answered by the
 * {@link ControlFlowAnalysis}.
 *
 * @template Specifics The language-specific Typir-Langium configuration.
 */
export class StatementsScopeProvider<Specifics extends TypirLangiumSpecifics> extends BaseScopeProvider<
    Specifics,
    ExpressionTypirServices<Specifics>
> {
    protected readonly reflection: AstReflection;
    protected readonly inference: TypeInferenceCollector<Specifics>;

    /**
     * Creates a new StatementsScopeProvider instance.
     *
     * @param typir The Typir services including type inference and language services.
     * @param statementTypes Type definitions for statement AST nodes.
     * @param expressionTypes Type definitions for expression AST nodes.
     * @param iterableType The class type used for iterable collections in for-loops.
     * @param controlFlow The control flow analysis of the language's statements
     */
    constructor(
        typir: ExpressionTypirServices<Specifics>,
        protected readonly statementTypes: StatementTypes,
        protected readonly expressionTypes: ExpressionTypes,
        protected iterableType: ClassType,
        protected readonly controlFlow: ControlFlowAnalysis<Specifics>
    ) {
        super(typir);
        this.reflection = typir.langium.LangiumServices.AstReflection;
        this.inference = typir.Inference;
    }

    override isScopeRelevantNode(node: Specifics["LanguageType"]): boolean {
        return (
            this.reflection.isInstance(node, this.statementTypes.statementsScopeType) ||
            this.reflection.isInstance(node, this.statementTypes.forStatementType)
        );
    }

    override createScope(
        languageNode: Specifics["LanguageType"],
        parentScope: BoundScope<Specifics> | undefined
    ): Scope<Specifics> {
        if (this.reflection.isInstance(languageNode, this.statementTypes.statementsScopeType)) {
            return this.createScopeForStatementsNode(languageNode, parentScope);
        } else if (this.reflection.isInstance(languageNode, this.statementTypes.forStatementType)) {
            return this.createScopeForForStatementNode(languageNode, parentScope);
        }
        throw new Error("Unsupported language node type for scope creation.");
    }

    /**
     * Creates a scope for a statements block node.
     *
     * The scope includes all variable declarations of the block.
     *
     * @param node The statements block AST node.
     * @param parentScope The parent scope containing this statements block, if any.
     * @returns A new scope for the statements block.
     */
    protected createScopeForStatementsNode(
        node: StatementsScopeType,
        parentScope: BoundScope<Specifics> | undefined
    ): Scope<Specifics> {
        return new StatementsScope<Specifics>(
            parentScope,
            (scope) => this.getStatementsScopeEntries(node, scope),
            node,
            this.statementTypes,
            this.reflection,
            this.controlFlow
        );
    }

    /**
     * Creates a scope for a for-statement node.
     *
     * The scope includes the loop iteration variable, which is accessible within
     * the for-loop body. The variable's type is inferred from the loop's iterable.
     *
     * @param node The for-statement AST node.
     * @param parentScope The parent scope containing this for-statement, if any.
     * @returns A new scope for the for-statement.
     */
    protected createScopeForForStatementNode(
        node: ForStatementType,
        parentScope: BoundScope<Specifics> | undefined
    ): Scope<Specifics> {
        const variable = node.variable;
        return new DefaultScope<Specifics>(
            parentScope,
            (scope) => {
                return [
                    {
                        name: variable.name,
                        position: -1,
                        languageNode: node,
                        definingScope: scope,
                        inferType: () => this.inference.inferType(node.variable)
                    }
                ];
            },
            [
                {
                    name: variable.name,
                    position: -1
                }
            ],
            node
        );
    }

    /**
     * Extracts scope entries from a statements block.
     *
     * Collects all variable declarations within the statements block, creating
     * scope entries with their names, positions, and type inference functions.
     *
     * @param node The statements block AST node.
     * @param scope The scope being populated with entries.
     * @returns An array of scope entries for variables declared in the block.
     */
    protected getStatementsScopeEntries(node: StatementsScopeType, scope: Scope<Specifics>): ScopeEntry<Specifics>[] {
        const entries: ScopeEntry<Specifics>[] = [];
        for (let i = 0; i < node.statements.length; i++) {
            const statement = node.statements[i];
            if (this.reflection.isInstance(statement, this.statementTypes.variableDeclarationStatementType)) {
                entries.push({
                    languageNode: statement,
                    name: statement.name,
                    position: i,
                    definingScope: scope,
                    inferType: () => this.inference.inferType(statement),
                    readonly: statement.isReadonly
                });
            }
        }
        return entries;
    }
}

/**
 * The scope of a statements block. Whether one of its variables is initialized before a
 * statement is decided by the control flow analysis.
 */
class StatementsScope<Specifics extends TypirLangiumSpecifics> extends DefaultScope<Specifics> {
    constructor(
        parent: BoundScope<Specifics> | undefined,
        entriesProvider: (scope: Scope<Specifics>) => ScopeEntry<Specifics>[],
        private readonly block: StatementsScopeType,
        private readonly statementTypes: StatementTypes,
        private readonly reflection: AstReflection,
        private readonly controlFlow: ControlFlowAnalysis<Specifics>
    ) {
        super(parent, entriesProvider, [], block);
    }

    override isEntryInitialized(entry: ScopeEntry<Specifics>, position: number): boolean {
        const declaration = entry.languageNode;
        const statement = this.block.statements[position];
        if (
            statement != undefined &&
            declaration != undefined &&
            this.reflection.isInstance(declaration, this.statementTypes.variableDeclarationStatementType)
        ) {
            return this.controlFlow.isAssignedBefore(statement, declaration);
        }
        return super.isEntryInitialized(entry, position);
    }
}
