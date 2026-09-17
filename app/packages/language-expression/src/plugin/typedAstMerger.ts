import type { ReturnType } from "../typir-extensions/config/type.js";
import type {
    TypedStatement,
    TypedExpression,
    TypedCallableBody,
    TypedIfStatement,
    TypedElseIfClause,
    TypedWhileStatement,
    TypedForStatement,
    TypedVariableDeclarationStatement,
    TypedAssignmentStatement,
    TypedExpressionStatement,
    TypedBreakStatement,
    TypedContinueStatement,
    TypedReturnStatement,
    TypedUnaryExpression,
    TypedBinaryExpression,
    TypedTernaryExpression,
    TypedExpressionCallExpression,
    TypedFunctionCallExpression,
    TypedMemberCallExpression,
    TypedCallArgument,
    TypedMemberAccessExpression,
    TypedIdentifierExpression,
    TypedStringLiteralExpression,
    TypedIntLiteralExpression,
    TypedLongLiteralExpression,
    TypedFloatLiteralExpression,
    TypedDoubleLiteralExpression,
    TypedBooleanLiteralExpression,
    TypedNullLiteralExpression,
    TypedListExpression,
    TypedAssertNonNullExpression,
    TypedTypeCastExpression,
    TypedTypeCheckExpression
} from "./typedAst.js";

/**
 * Base helper class for merging multiple typed ASTs with different type arrays
 * into a single global type array with remapped indices.
 * Can be extended for language-specific remapping (e.g., script-specific features like lambdas).
 */
export abstract class TypedAstMerger {
    /**
     * The global merged types array.
     */
    private readonly globalTypes: ReturnType[] = [];

    /**
     * Map from serialized type to its index in the global types array.
     */
    private readonly typeToIndex = new Map<string, number>();

    /**
     * Map from type array reference to its index mapping (old index -> new index).
     */
    private readonly typesArrayMappings = new Map<ReturnType[], Map<number, number>>();

    /**
     * Gets the merged global types array.
     *
     * @returns The array of all unique types
     */
    getGlobalTypes(): ReturnType[] {
        return this.globalTypes;
    }

    /**
     * Indexes a type array and creates a mapping from old indices to new global indices.
     * Uses reference comparison to avoid re-indexing the same array.
     *
     * @param typesArray The type array to index
     * @returns A map from old index to new global index
     */
    indexTypesArray(typesArray: ReturnType[]): Map<number, number> {
        const existingMapping = this.typesArrayMappings.get(typesArray);
        if (existingMapping !== undefined) {
            return existingMapping;
        }

        const mapping = new Map<number, number>();
        for (let oldIndex = 0; oldIndex < typesArray.length; oldIndex++) {
            const type = typesArray[oldIndex];
            const newIndex = this.addTypeToGlobal(type);
            mapping.set(oldIndex, newIndex);
        }

        this.typesArrayMappings.set(typesArray, mapping);
        return mapping;
    }

    /**
     * Adds a type to the global types array if it doesn't already exist.
     *
     * @param type The type to add
     * @returns The index of the type in the global array
     */
    addTypeToGlobal(type: ReturnType): number {
        const typeKey = JSON.stringify(type);
        const existingIndex = this.typeToIndex.get(typeKey);
        if (existingIndex !== undefined) {
            return existingIndex;
        }

        const newIndex = this.globalTypes.length;
        this.globalTypes.push(type);
        this.typeToIndex.set(typeKey, newIndex);
        return newIndex;
    }

    /**
     * Remaps a callable body to use global type indices.
     *
     * @param body The callable body to remap
     * @param mapping The index mapping
     * @returns A new callable body with remapped type indices
     */
    protected remapCallableBody(body: TypedCallableBody, mapping: Map<number, number>): TypedCallableBody {
        return {
            body: body.body.map((stmt) => this.remapStatement(stmt, mapping))
        };
    }

    /**
     * Remaps a statement to use global type indices.
     * Can be extended by subclasses to handle additional statement types.
     *
     * @param stmt The statement to remap
     * @param mapping The index mapping
     * @returns A new statement with remapped type indices
     */
    protected remapStatement(stmt: TypedStatement, mapping: Map<number, number>): TypedStatement {
        switch (stmt.kind) {
            case "if":
                return this.remapIfStatement(stmt as TypedIfStatement, mapping);
            case "while":
                return this.remapWhileStatement(stmt as TypedWhileStatement, mapping);
            case "for":
                return this.remapForStatement(stmt as TypedForStatement, mapping);
            case "variableDeclaration":
                return this.remapVariableDeclarationStatement(stmt as TypedVariableDeclarationStatement, mapping);
            case "assignment":
                return this.remapAssignmentStatement(stmt as TypedAssignmentStatement, mapping);
            case "expression":
                return this.remapExpressionStatement(stmt as TypedExpressionStatement, mapping);
            case "break":
                return this.remapBreakStatement(stmt as TypedBreakStatement);
            case "continue":
                return this.remapContinueStatement(stmt as TypedContinueStatement);
            case "return":
                return this.remapReturnStatement(stmt as TypedReturnStatement, mapping);
        }
        return this.remapAdditionalStatement(stmt, mapping);
    }

    /**
     * Hook for subclasses to handle additional statement types not covered by the base merger.
     * Should throw an error if the statement type is not supported.
     *
     * @param stmt The statement to remap
     * @param _mapping The index mapping
     * @returns A new statement with remapped type indices
     */
    protected remapAdditionalStatement(stmt: TypedStatement, _mapping: Map<number, number>): TypedStatement {
        throw new Error(`Unhandled statement kind: ${stmt.kind}`);
    }

    /**
     * Remaps an if statement.
     *
     * @param stmt The if statement to remap
     * @param mapping The index mapping
     * @returns A new if statement with remapped type indices
     */
    protected remapIfStatement(stmt: TypedIfStatement, mapping: Map<number, number>): TypedIfStatement {
        return {
            kind: "if",
            condition: this.remapExpression(stmt.condition, mapping),
            thenBlock: stmt.thenBlock.map((s) => this.remapStatement(s, mapping)),
            elseIfs: stmt.elseIfs.map((ei) => this.remapElseIfClause(ei, mapping)),
            elseBlock: stmt.elseBlock?.map((s) => this.remapStatement(s, mapping))
        };
    }

    /**
     * Remaps an else-if clause.
     *
     * @param clause The else-if clause to remap
     * @param mapping The index mapping
     * @returns A new else-if clause with remapped type indices
     */
    protected remapElseIfClause(clause: TypedElseIfClause, mapping: Map<number, number>): TypedElseIfClause {
        return {
            condition: this.remapExpression(clause.condition, mapping),
            thenBlock: clause.thenBlock.map((s) => this.remapStatement(s, mapping))
        };
    }

    /**
     * Remaps a while statement.
     *
     * @param stmt The while statement to remap
     * @param mapping The index mapping
     * @returns A new while statement with remapped type indices
     */
    protected remapWhileStatement(stmt: TypedWhileStatement, mapping: Map<number, number>): TypedWhileStatement {
        return {
            kind: "while",
            condition: this.remapExpression(stmt.condition, mapping),
            body: stmt.body.map((s) => this.remapStatement(s, mapping))
        };
    }

    /**
     * Remaps a for statement.
     *
     * @param stmt The for statement to remap
     * @param mapping The index mapping
     * @returns A new for statement with remapped type indices
     */
    protected remapForStatement(stmt: TypedForStatement, mapping: Map<number, number>): TypedForStatement {
        return {
            kind: "for",
            variableName: stmt.variableName,
            variableType: mapping.get(stmt.variableType)!,
            iterable: this.remapExpression(stmt.iterable, mapping),
            body: stmt.body.map((s) => this.remapStatement(s, mapping))
        };
    }

    /**
     * Remaps a variable declaration statement.
     *
     * @param stmt The variable declaration statement to remap
     * @param mapping The index mapping
     * @returns A new variable declaration statement with remapped type indices
     */
    protected remapVariableDeclarationStatement(
        stmt: TypedVariableDeclarationStatement,
        mapping: Map<number, number>
    ): TypedVariableDeclarationStatement {
        return {
            kind: "variableDeclaration",
            name: stmt.name,
            type: mapping.get(stmt.type)!,
            initialValue: stmt.initialValue ? this.remapExpression(stmt.initialValue, mapping) : undefined
        };
    }

    /**
     * Remaps an assignment statement.
     *
     * @param stmt The assignment statement to remap
     * @param mapping The index mapping
     * @returns A new assignment statement with remapped type indices
     */
    protected remapAssignmentStatement(
        stmt: TypedAssignmentStatement,
        mapping: Map<number, number>
    ): TypedAssignmentStatement {
        return {
            kind: "assignment",
            left: this.remapExpression(stmt.left, mapping) as TypedIdentifierExpression | TypedMemberAccessExpression,
            right: this.remapExpression(stmt.right, mapping)
        };
    }

    /**
     * Remaps an expression statement.
     *
     * @param stmt The expression statement to remap
     * @param mapping The index mapping
     * @returns A new expression statement with remapped type indices
     */
    protected remapExpressionStatement(
        stmt: TypedExpressionStatement,
        mapping: Map<number, number>
    ): TypedExpressionStatement {
        return {
            kind: "expression",
            expression: this.remapExpression(stmt.expression, mapping)
        };
    }

    /**
     * Remaps a break statement.
     *
     * @param stmt The break statement to remap
     * @returns The break statement unchanged
     */
    protected remapBreakStatement(stmt: TypedBreakStatement): TypedBreakStatement {
        return stmt;
    }

    /**
     * Remaps a continue statement.
     *
     * @param stmt The continue statement to remap
     * @returns The continue statement unchanged
     */
    protected remapContinueStatement(stmt: TypedContinueStatement): TypedContinueStatement {
        return stmt;
    }

    /**
     * Remaps a return statement.
     *
     * @param stmt The return statement to remap
     * @param mapping The index mapping
     * @returns A new return statement with remapped type indices
     */
    protected remapReturnStatement(stmt: TypedReturnStatement, mapping: Map<number, number>): TypedReturnStatement {
        return {
            kind: "return",
            value: stmt.value ? this.remapExpression(stmt.value, mapping) : undefined
        };
    }

    /**
     * Remaps an expression to use global type indices.
     * Can be extended by subclasses to handle additional expression types.
     *
     * @param expr The expression to remap
     * @param mapping The index mapping
     * @returns A new expression with remapped type indices
     */
    protected remapExpression(expr: TypedExpression, mapping: Map<number, number>): TypedExpression {
        switch (expr.kind) {
            case "unary":
                return this.remapUnaryExpression(expr as TypedUnaryExpression, mapping);
            case "binary":
                return this.remapBinaryExpression(expr as TypedBinaryExpression, mapping);
            case "ternary":
                return this.remapTernaryExpression(expr as TypedTernaryExpression, mapping);
            case "assertNonNull":
                return this.remapAssertNonNullExpression(expr as TypedAssertNonNullExpression, mapping);
            case "typeCast":
                return this.remapTypeCastExpression(expr as TypedTypeCastExpression, mapping);
            case "typeCheck":
                return this.remapTypeCheckExpression(expr as TypedTypeCheckExpression, mapping);
            case "call":
                return this.remapExpressionCallExpression(expr as TypedExpressionCallExpression, mapping);
            case "functionCall":
                return this.remapFunctionCallExpression(expr as TypedFunctionCallExpression, mapping);
            case "memberCall":
                return this.remapMemberCallExpression(expr as TypedMemberCallExpression, mapping);
            case "memberAccess":
                return this.remapMemberAccessExpression(expr as TypedMemberAccessExpression, mapping);
            case "identifier":
                return this.remapIdentifierExpression(expr as TypedIdentifierExpression, mapping);
            case "stringLiteral":
                return this.remapStringLiteralExpression(expr as TypedStringLiteralExpression, mapping);
            case "intLiteral":
                return this.remapIntLiteralExpression(expr as TypedIntLiteralExpression, mapping);
            case "longLiteral":
                return this.remapLongLiteralExpression(expr as TypedLongLiteralExpression, mapping);
            case "floatLiteral":
                return this.remapFloatLiteralExpression(expr as TypedFloatLiteralExpression, mapping);
            case "doubleLiteral":
                return this.remapDoubleLiteralExpression(expr as TypedDoubleLiteralExpression, mapping);
            case "booleanLiteral":
                return this.remapBooleanLiteralExpression(expr as TypedBooleanLiteralExpression, mapping);
            case "nullLiteral":
                return this.remapNullLiteralExpression(expr as TypedNullLiteralExpression, mapping);
            case "listLiteral":
                return this.remapListExpression(expr as TypedListExpression, mapping);
        }
        return this.remapAdditionalExpression(expr, mapping);
    }

    /**
     * Hook for subclasses to handle additional expression types not covered by the base merger.
     * Should throw an error if the expression type is not supported.
     *
     * @param expr The expression to remap
     * @param _ The index mapping
     * @returns A new expression with remapped type indices
     */
    protected remapAdditionalExpression(expr: TypedExpression, _: Map<number, number>): TypedExpression {
        throw new Error(`Unhandled expression kind: ${expr.kind}`);
    }

    /**
     * Remaps a unary expression.
     *
     * @param expr The unary expression to remap
     * @param mapping The index mapping
     * @returns A new unary expression with remapped type indices
     */
    protected remapUnaryExpression(expr: TypedUnaryExpression, mapping: Map<number, number>): TypedUnaryExpression {
        return {
            kind: "unary",
            evalType: mapping.get(expr.evalType)!,
            operator: expr.operator,
            expression: this.remapExpression(expr.expression, mapping)
        };
    }

    /**
     * Remaps a binary expression.
     *
     * @param expr The binary expression to remap
     * @param mapping The index mapping
     * @returns A new binary expression with remapped type indices
     */
    protected remapBinaryExpression(expr: TypedBinaryExpression, mapping: Map<number, number>): TypedBinaryExpression {
        return {
            kind: "binary",
            evalType: mapping.get(expr.evalType)!,
            operator: expr.operator,
            left: this.remapExpression(expr.left, mapping),
            right: this.remapExpression(expr.right, mapping)
        };
    }

    /**
     * Remaps a ternary expression.
     *
     * @param expr The ternary expression to remap
     * @param mapping The index mapping
     * @returns A new ternary expression with remapped type indices
     */
    protected remapTernaryExpression(
        expr: TypedTernaryExpression,
        mapping: Map<number, number>
    ): TypedTernaryExpression {
        return {
            kind: "ternary",
            evalType: mapping.get(expr.evalType)!,
            condition: this.remapExpression(expr.condition, mapping),
            trueExpression: this.remapExpression(expr.trueExpression, mapping),
            falseExpression: this.remapExpression(expr.falseExpression, mapping)
        };
    }

    /**
     * Remaps an assert non-null expression.
     *
     * @param expr The assert non-null expression to remap
     * @param mapping The index mapping
     * @returns A new assert non-null expression with remapped type indices
     */
    protected remapAssertNonNullExpression(
        expr: TypedAssertNonNullExpression,
        mapping: Map<number, number>
    ): TypedAssertNonNullExpression {
        return {
            kind: "assertNonNull",
            evalType: mapping.get(expr.evalType)!,
            expression: this.remapExpression(expr.expression, mapping)
        };
    }

    /**
     * Remaps a type cast expression.
     *
     * @param expr The type cast expression to remap
     * @param mapping The index mapping
     * @returns A new type cast expression with remapped type indices
     */
    protected remapTypeCastExpression(
        expr: TypedTypeCastExpression,
        mapping: Map<number, number>
    ): TypedTypeCastExpression {
        return {
            kind: "typeCast",
            evalType: mapping.get(expr.evalType)!,
            expression: this.remapExpression(expr.expression, mapping),
            targetType: mapping.get(expr.targetType)!,
            isSafe: expr.isSafe
        };
    }

    /**
     * Remaps a type check expression.
     *
     * @param expr The type check expression to remap
     * @param mapping The index mapping
     * @returns A new type check expression with remapped type indices
     */
    protected remapTypeCheckExpression(
        expr: TypedTypeCheckExpression,
        mapping: Map<number, number>
    ): TypedTypeCheckExpression {
        return {
            kind: "typeCheck",
            evalType: mapping.get(expr.evalType)!,
            expression: this.remapExpression(expr.expression, mapping),
            checkType: mapping.get(expr.checkType)!,
            isNegated: expr.isNegated
        };
    }

    /**
     * Remaps an expression call expression.
     *
     * @param expr The expression call expression to remap
     * @param mapping The index mapping
     * @returns A new expression call expression with remapped type indices
     */
    protected remapExpressionCallExpression(
        expr: TypedExpressionCallExpression,
        mapping: Map<number, number>
    ): TypedExpressionCallExpression {
        return {
            kind: "call",
            evalType: mapping.get(expr.evalType)!,
            expression: this.remapExpression(expr.expression, mapping),
            arguments: expr.arguments.map((arg) => this.remapCallArgument(arg, mapping))
        };
    }

    /**
     * Remaps a function call expression.
     *
     * @param expr The function call expression to remap
     * @param mapping The index mapping
     * @returns A new function call expression with remapped type indices
     */
    protected remapFunctionCallExpression(
        expr: TypedFunctionCallExpression,
        mapping: Map<number, number>
    ): TypedFunctionCallExpression {
        return {
            kind: "functionCall",
            evalType: mapping.get(expr.evalType)!,
            name: expr.name,
            overload: expr.overload,
            arguments: expr.arguments.map((arg) => this.remapCallArgument(arg, mapping))
        };
    }

    /**
     * Remaps a member call expression.
     *
     * @param expr The member call expression to remap
     * @param mapping The index mapping
     * @returns A new member call expression with remapped type indices
     */
    protected remapMemberCallExpression(
        expr: TypedMemberCallExpression,
        mapping: Map<number, number>
    ): TypedMemberCallExpression {
        return {
            kind: "memberCall",
            evalType: mapping.get(expr.evalType)!,
            expression: this.remapExpression(expr.expression, mapping),
            member: expr.member,
            isNullChaining: expr.isNullChaining,
            overload: expr.overload,
            arguments: expr.arguments.map((arg) => this.remapCallArgument(arg, mapping))
        };
    }

    /**
     * Remaps a call argument, updating both the expression and parameter type indices.
     *
     * @param arg The call argument to remap
     * @param mapping The index mapping
     * @returns A new call argument with remapped type indices
     */
    protected remapCallArgument(arg: TypedCallArgument, mapping: Map<number, number>): TypedCallArgument {
        return {
            value: this.remapExpression(arg.value, mapping),
            parameterType: mapping.get(arg.parameterType)!,
            ...(arg.parameter != undefined ? { parameter: arg.parameter } : {})
        };
    }

    /**
     * Remaps a member access expression.
     *
     * @param expr The member access expression to remap
     * @param mapping The index mapping
     * @returns A new member access expression with remapped type indices
     */
    protected remapMemberAccessExpression(
        expr: TypedMemberAccessExpression,
        mapping: Map<number, number>
    ): TypedMemberAccessExpression {
        return {
            kind: "memberAccess",
            evalType: mapping.get(expr.evalType)!,
            expression: this.remapExpression(expr.expression, mapping),
            member: expr.member,
            isNullChaining: expr.isNullChaining
        };
    }

    /**
     * Remaps an identifier expression.
     *
     * @param expr The identifier expression to remap
     * @param mapping The index mapping
     * @returns A new identifier expression with remapped type indices
     */
    protected remapIdentifierExpression(
        expr: TypedIdentifierExpression,
        mapping: Map<number, number>
    ): TypedIdentifierExpression {
        return {
            kind: "identifier",
            evalType: mapping.get(expr.evalType)!,
            name: expr.name,
            scope: expr.scope
        };
    }

    /**
     * Remaps a string literal expression.
     *
     * @param expr The string literal expression to remap
     * @param mapping The index mapping
     * @returns A new string literal expression with remapped type indices
     */
    protected remapStringLiteralExpression(
        expr: TypedStringLiteralExpression,
        mapping: Map<number, number>
    ): TypedStringLiteralExpression {
        return {
            kind: "stringLiteral",
            evalType: mapping.get(expr.evalType)!,
            value: expr.value
        };
    }

    /**
     * Remaps an int literal expression.
     *
     * @param expr The int literal expression to remap
     * @param mapping The index mapping
     * @returns A new int literal expression with remapped type indices
     */
    protected remapIntLiteralExpression(
        expr: TypedIntLiteralExpression,
        mapping: Map<number, number>
    ): TypedIntLiteralExpression {
        return {
            kind: "intLiteral",
            evalType: mapping.get(expr.evalType)!,
            value: expr.value
        };
    }

    /**
     * Remaps a long literal expression.
     *
     * @param expr The long literal expression to remap
     * @param mapping The index mapping
     * @returns A new long literal expression with remapped type indices
     */
    protected remapLongLiteralExpression(
        expr: TypedLongLiteralExpression,
        mapping: Map<number, number>
    ): TypedLongLiteralExpression {
        return {
            kind: "longLiteral",
            evalType: mapping.get(expr.evalType)!,
            value: expr.value
        };
    }

    /**
     * Remaps a float literal expression.
     *
     * @param expr The float literal expression to remap
     * @param mapping The index mapping
     * @returns A new float literal expression with remapped type indices
     */
    protected remapFloatLiteralExpression(
        expr: TypedFloatLiteralExpression,
        mapping: Map<number, number>
    ): TypedFloatLiteralExpression {
        return {
            kind: "floatLiteral",
            evalType: mapping.get(expr.evalType)!,
            value: expr.value
        };
    }

    /**
     * Remaps a double literal expression.
     *
     * @param expr The double literal expression to remap
     * @param mapping The index mapping
     * @returns A new double literal expression with remapped type indices
     */
    protected remapDoubleLiteralExpression(
        expr: TypedDoubleLiteralExpression,
        mapping: Map<number, number>
    ): TypedDoubleLiteralExpression {
        return {
            kind: "doubleLiteral",
            evalType: mapping.get(expr.evalType)!,
            value: expr.value
        };
    }

    /**
     * Remaps a boolean literal expression.
     *
     * @param expr The boolean literal expression to remap
     * @param mapping The index mapping
     * @returns A new boolean literal expression with remapped type indices
     */
    protected remapBooleanLiteralExpression(
        expr: TypedBooleanLiteralExpression,
        mapping: Map<number, number>
    ): TypedBooleanLiteralExpression {
        return {
            kind: "booleanLiteral",
            evalType: mapping.get(expr.evalType)!,
            value: expr.value
        };
    }

    /**
     * Remaps a null literal expression.
     *
     * @param expr The null literal expression to remap
     * @param mapping The index mapping
     * @returns A new null literal expression with remapped type indices
     */
    protected remapNullLiteralExpression(
        expr: TypedNullLiteralExpression,
        mapping: Map<number, number>
    ): TypedNullLiteralExpression {
        return {
            kind: "nullLiteral",
            evalType: mapping.get(expr.evalType)!
        };
    }

    /**
     * Remaps a list expression.
     *
     * @param expr The list expression to remap
     * @param mapping The index mapping
     * @returns A new list expression with remapped type indices
     */
    protected remapListExpression(expr: TypedListExpression, mapping: Map<number, number>): TypedListExpression {
        return {
            kind: "listLiteral",
            evalType: mapping.get(expr.evalType)!,
            elements: expr.elements.map((element) => this.remapExpression(element, mapping))
        };
    }
}
