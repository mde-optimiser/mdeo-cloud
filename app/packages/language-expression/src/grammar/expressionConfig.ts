/**
 * Configuration for expressions
 */
export class ExpressionConfig {
    /**
     * The name for the BaseExpression type.
     */
    readonly baseExpressionTypeName: string;

    /**
     * The name for the PrimaryExpression rule.
     */
    readonly primaryExpressionRuleName: string;

    /**
     * The name for the UnaryExpression type.
     */
    readonly unaryExpressionTypeName: string;

    /**
     * The name for the UnaryExpression rule.
     */
    readonly unaryExpressionRuleName: string;

    /**
     * The name for the BinaryExpression type.
     */
    readonly binaryExpressionTypeName: string;

    /**
     * The name for the BinaryExpression rule.
     */
    readonly binaryExpressionRuleName: string;

    /**
     * The name for the TernaryExpression type.
     */
    readonly ternaryExpressionTypeName: string;

    /**
     * The name for the TernaryExpression rule.
     */
    readonly ternaryExpressionRuleName: string;

    /**
     * The name for the CallExpression type.
     */
    readonly callExpressionTypeName: string;

    /**
     * The name for the CallExpressionGenericArgs type.
     */
    readonly callExpressionGenericArgsTypeName: string;

    /**
     * The name for the CallExpressionGenericArgs rule.
     */
    readonly callExpressionGenericArgsRuleName: string;

    /**
     * The name for the NamedArgument type.
     */
    readonly namedArgumentTypeName: string;

    /**
     * The name for the NamedArgument rule.
     */
    readonly namedArgumentRuleName: string;

    /**
     * The name for the MemberAccessExpression type.
     */
    readonly memberAccessExpressionTypeName: string;

    /**
     * The name for the MemberCallExpression type.
     */
    readonly memberCallExpressionTypeName: string;

    /**
     * The name for the AssertNonNullPostfixFragment rule.
     */
    readonly assertNonNullPostfixFragmentRuleName: string;

    /**
     * The name for the MemberAccessPostfixFragment rule.
     */
    readonly memberAccessPostfixFragmentRuleName: string;

    /**
     * The name for the CallPostfixFragment rule.
     */
    readonly callPostfixFragmentRuleName: string;

    /**
     * The name for the MemberCallPostfixFragment rule.
     */
    readonly memberCallPostfixFragmentRuleName: string;

    /**
     * The name for the PostfixExpression rule.
     */
    readonly postfixExpressionRuleName: string;

    /**
     * The name for the IdentifierExpression type.
     */
    readonly identifierExpressionTypeName: string;

    /**
     * The name for the IdentifierExpression rule.
     */
    readonly identifierExpressionRuleName: string;

    /**
     * The name for the StringLiteralExpression type.
     */
    readonly stringLiteralExpressionTypeName: string;

    /**
     * The name for the StringLiteralExpression rule.
     */
    readonly stringLiteralExpressionRuleName: string;

    /**
     * The name for the IntLiteralExpression type.
     */
    readonly intLiteralExpressionTypeName: string;

    /**
     * The name for the IntLiteralExpression rule.
     */
    readonly intLiteralExpressionRuleName: string;

    /**
     * The name for the LongLiteralExpression type.
     */
    readonly longLiteralExpressionTypeName: string;

    /**
     * The name for the LongLiteralExpression rule.
     */
    readonly longLiteralExpressionRuleName: string;

    /**
     * The name for the FloatLiteralExpression type.
     */
    readonly floatLiteralExpressionTypeName: string;

    /**
     * The name for the FloatLiteralExpression rule.
     */
    readonly floatLiteralExpressionRuleName: string;

    /**
     * The name for the DoubleLiteralExpression type.
     */
    readonly doubleLiteralExpressionTypeName: string;

    /**
     * The name for the DoubleLiteralExpression rule.
     */
    readonly doubleLiteralExpressionRuleName: string;

    /**
     * The name for the BooleanLiteralExpression type.
     */
    readonly booleanLiteralExpressionTypeName: string;

    /**
     * The name for the BooleanLiteralExpression rule.
     */
    readonly booleanLiteralExpressionRuleName: string;

    /**
     * The name for the NullLiteralExpression type.
     */
    readonly nullLiteralExpressionTypeName: string;

    /**
     * The name for the BracketedExpression type.
     */
    readonly bracketedExpressionTypeName: string;

    /**
     * The name for the BracketedExpression rule.
     */
    readonly bracketedExpressionRuleName: string;

    /**
     * The name for the Expression rule.
     */
    readonly expressionRuleName: string;

    /**
     * The name for the TypeCastExpression type.
     */
    readonly typeCastExpressionTypeName: string;

    /**
     * The name for the TypeCheckExpression type.
     */
    readonly typeCheckExpressionTypeName: string;

    /**
     * The name for the AssertNonNullExpression type.
     */
    readonly assertNonNullExpressionTypeName: string;

    /**
     * The name for the ListExpression type.
     */
    readonly listExpressionTypeName: string;

    /**
     * The name for the ListExpression rule.
     */
    readonly listExpressionRuleName: string;

    /**
     * The name for the TypeCastExpression rule.
     */
    readonly typeCastExpressionRuleName: string;

    /**
     * The name for the TypeCheckExpression rule.
     */
    readonly typeCheckExpressionRuleName: string;

    /**
     * The name for the lower precedence BinaryExpression rule (comparison operators).
     */
    readonly binaryExpressionLowerRuleName: string;

    /**
     * The name for the higher precedence BinaryExpression rule (additive and above).
     */
    readonly binaryExpressionUpperRuleName: string;

    /**
     * Creates a new ExpressionConfig.
     * @param prefix Prefix for naming generated rules and types.
     */
    constructor(readonly prefix: string) {
        this.baseExpressionTypeName = prefix + "BaseExpression";
        this.primaryExpressionRuleName = prefix + "PrimaryExpressionRule";
        this.unaryExpressionTypeName = prefix + "UnaryExpression";
        this.unaryExpressionRuleName = prefix + "UnaryExpressionRule";
        this.binaryExpressionTypeName = prefix + "BinaryExpression";
        this.binaryExpressionRuleName = prefix + "BinaryExpression";
        this.ternaryExpressionTypeName = prefix + "TernaryExpression";
        this.ternaryExpressionRuleName = prefix + "TernaryExpressionRule";
        this.callExpressionTypeName = prefix + "CallExpression";
        this.callExpressionGenericArgsTypeName = prefix + "CallExpressionGenericArgs";
        this.callExpressionGenericArgsRuleName = prefix + "CallExpressionGenericArgsRule";
        this.namedArgumentTypeName = prefix + "NamedArgument";
        this.namedArgumentRuleName = prefix + "NamedArgumentRule";
        this.memberAccessExpressionTypeName = prefix + "MemberAccessExpression";
        this.memberCallExpressionTypeName = prefix + "MemberCallExpression";
        this.postfixExpressionRuleName = prefix + "PostfixExpressionRule";
        this.assertNonNullPostfixFragmentRuleName = prefix + "AssertNonNullPostfixFragmentRule";
        this.memberAccessPostfixFragmentRuleName = prefix + "MemberAccessPostfixFragmentRule";
        this.callPostfixFragmentRuleName = prefix + "CallPostfixFragmentRule";
        this.memberCallPostfixFragmentRuleName = prefix + "MemberCallPostfixFragmentRule";
        this.identifierExpressionTypeName = prefix + "IdentifierExpression";
        this.identifierExpressionRuleName = prefix + "IdentifierExpressionRule";
        this.stringLiteralExpressionTypeName = prefix + "StringLiteralExpression";
        this.stringLiteralExpressionRuleName = prefix + "StringLiteralExpressionRule";
        this.intLiteralExpressionTypeName = prefix + "IntLiteralExpression";
        this.intLiteralExpressionRuleName = prefix + "IntLiteralExpressionRule";
        this.longLiteralExpressionTypeName = prefix + "LongLiteralExpression";
        this.longLiteralExpressionRuleName = prefix + "LongLiteralExpressionRule";
        this.floatLiteralExpressionTypeName = prefix + "FloatLiteralExpression";
        this.floatLiteralExpressionRuleName = prefix + "FloatLiteralExpressionRule";
        this.doubleLiteralExpressionTypeName = prefix + "DoubleLiteralExpression";
        this.doubleLiteralExpressionRuleName = prefix + "DoubleLiteralExpressionRule";
        this.booleanLiteralExpressionTypeName = prefix + "BooleanLiteralExpression";
        this.booleanLiteralExpressionRuleName = prefix + "BooleanLiteralExpressionRule";
        this.nullLiteralExpressionTypeName = prefix + "NullLiteralExpression";
        this.bracketedExpressionTypeName = prefix + "BracketedExpression";
        this.bracketedExpressionRuleName = prefix + "BracketedExpressionRule";
        this.expressionRuleName = prefix + "ExpressionRule";
        this.typeCastExpressionTypeName = prefix + "TypeCastExpression";
        this.typeCheckExpressionTypeName = prefix + "TypeCheckExpression";
        this.assertNonNullExpressionTypeName = prefix + "AssertNonNullExpression";
        this.listExpressionTypeName = prefix + "ListExpression";
        this.listExpressionRuleName = prefix + "ListExpressionRule";
        this.typeCastExpressionRuleName = prefix + "TypeCastExpressionRule";
        this.typeCheckExpressionRuleName = prefix + "TypeCheckExpressionRule";
        this.binaryExpressionLowerRuleName = prefix + "BinaryExpressionLower";
        this.binaryExpressionUpperRuleName = prefix + "BinaryExpressionUpper";
    }
}
