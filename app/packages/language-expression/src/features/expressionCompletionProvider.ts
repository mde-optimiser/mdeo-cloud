import type { TypirLangiumSpecifics } from "typir-langium";
import type { ScopeProvider } from "../typir-extensions/scope/scopeProvider.js";
import type { CustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomFunctionType } from "../typir-extensions/kinds/custom-function/custom-function-type.js";
import type { ExtendedTypirServices } from "../typir-extensions/service/extendedTypirServices.js";
import { sharedImport, BaseCompletionProvider } from "@mdeo/language-shared";
import type { CompletionAcceptor, CompletionContext, CompletionProviderOptions, NextFeature } from "langium/lsp";
import type { CompletionItemKind as CompletionItemKindType } from "vscode-languageserver-protocol";
import type { MaybePromise, AstUtils as AstUtilsType } from "langium";
import type { ExpressionTypirServices } from "../type-system/services.js";
import {
    ID,
    type AstReflection,
    type AstSerializerAdditionalServices,
    type ExtendedLangiumServices
} from "@mdeo/language-common";
import type { ExpressionTypes } from "../grammar/expressionTypes.js";
import type { TypeTypes } from "../grammar/typeTypes.js";
import { type ClassType } from "../typir-extensions/config/type.js";
import { memberValueTypeToString, memberMethodDetailString } from "./expressionTypeRendering.js";

const { CompletionItemKind } = sharedImport("vscode-languageserver-protocol");
const { AstUtils } = sharedImport("langium");

/**
 * Completion provider that extends Langium's default completion provider to support
 * completion for expression-level constructs that use Typir's scope system instead
 * of Langium's cross-reference scoping.
 *
 * Handles:
 * - IdentifierExpression names (variables, functions, etc. from Typir scopes)
 * - MemberAccessExpression / MemberCallExpression members (properties/methods from inferred types)
 */
export class ExpressionCompletionProvider extends BaseCompletionProvider {
    private readonly typirScopeProvider: ScopeProvider<TypirLangiumSpecifics>;
    private readonly typirInference: ExtendedTypirServices<TypirLangiumSpecifics>["Inference"];
    protected readonly reflection: AstReflection;
    private readonly typir: ExpressionTypirServices<TypirLangiumSpecifics>;
    override readonly completionOptions: CompletionProviderOptions = {
        triggerCharacters: ["."]
    };

    /**
     * Creates a new ExpressionCompletionProvider.
     *
     * @param services The combined Langium and Typir services
     * @param expressionTypes Grammar-derived AST type descriptors used to identify expression node types
     * @param typeTypes Optional type types from the grammar, needed to enable type annotation completion
     */
    constructor(
        services: {
            typir: ExpressionTypirServices<TypirLangiumSpecifics>;
        } & ExtendedLangiumServices &
            AstSerializerAdditionalServices,
        protected readonly expressionTypes: ExpressionTypes,
        protected readonly typeTypes?: TypeTypes
    ) {
        super(services);
        this.typir = services.typir;
        this.typirScopeProvider = services.typir.ScopeProvider;
        this.typirInference = services.typir.Inference;
        this.reflection = services.shared.AstReflection;
    }

    /**
     * Routes completion to the appropriate handler based on the grammar feature being completed.
     *
     * Handles identifier expressions, member access/call expressions, and type annotation
     * expressions specifically. All other features are delegated to the default provider.
     *
     * @param context The current completion context
     * @param next Describes the grammar feature being completed, including its type and property
     * @param acceptor The acceptor function to register completion items
     * @returns A promise or void when completion is complete
     */
    protected override completionFor(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): MaybePromise<void> {
        if (next.type === this.expressionTypes.identifierExpressionType.name) {
            return this.completionForIdentifierExpression(context, acceptor);
        }
        if (
            next.type === this.expressionTypes.memberAccessExpressionType.name ||
            next.type === this.expressionTypes.memberCallExpressionType.name
        ) {
            return this.completionForMemberAccess(context, acceptor);
        }
        if (this.typeTypes != undefined && next.type === this.typeTypes.classTypeType.name) {
            return this.completionForTypeAnnotation(context, next, acceptor);
        }

        if (
            this.typeTypes != undefined &&
            next.type === undefined &&
            next.property === "name" &&
            context.node != undefined &&
            this.reflection.isInstance(context.node, this.typeTypes.classTypeType)
        ) {
            return this.completionForTypeAnnotation(context, next, acceptor);
        }
        return super.completionFor(context, next, acceptor);
    }

    /**
     * Provides completion items for type annotation expressions.
     *
     * Uses the {@link DocumentPackageCacheService} and {@link TypeDefinitionService} to compute
     * completion candidates directly. When a package name is already typed (e.g., "class."),
     * only types within that package are suggested. Otherwise all valid package names and
     * unambiguous type names are suggested.
     *
     * @param context The current completion context
     * @param next Describes the grammar feature being completed
     * @param acceptor The acceptor function to register completion items
     * @returns void
     */
    private completionForTypeAnnotation(
        context: CompletionContext,
        next: NextFeature,
        acceptor: CompletionAcceptor
    ): void {
        const node = context.node;
        if (node == undefined) {
            return;
        }
        try {
            const document = (AstUtils as typeof AstUtilsType).getDocument(node);
            const { packageMap, allInternalPackages, typeAliases } =
                this.typir.PackageMapCache.getDocumentPackageCache(document);
            const nodeAsClassType = node as { packageName?: string };
            const hasPackageName = nodeAsClassType.packageName != undefined && next.property === "name";

            if (hasPackageName) {
                const packageName = nodeAsClassType.packageName!;
                const internalPackages = packageMap.get(packageName);
                if (internalPackages == undefined) {
                    return;
                }
                const internalPackageSet = new Set(internalPackages);
                const seen = new Set<string>();
                for (const type of this.typir.TypeDefinitions.getAllClassTypes()) {
                    if (type.isVirtual !== true && internalPackageSet.has(type.package) && !seen.has(type.name)) {
                        seen.add(type.name);
                        acceptor(context, {
                            label: type.name,
                            insertText: this.astSerializer.serializePrimitive({ value: type.name }, ID),
                            kind: CompletionItemKind.Class,
                            sortText: "0"
                        });
                    }
                }
            } else {
                const relevantTypes = this.typir.TypeDefinitions.getAllClassTypes().filter(
                    (type) => type.isVirtual !== true && allInternalPackages.has(type.package)
                );
                const byName = new Map<string, ClassType[]>();
                for (const type of relevantTypes) {
                    const list = byName.get(type.name) ?? [];
                    list.push(type);
                    byName.set(type.name, list);
                }
                for (const alias of typeAliases.keys()) {
                    acceptor(context, {
                        label: alias,
                        insertText: this.astSerializer.serializePrimitive({ value: alias }, ID),
                        kind: CompletionItemKind.Class,
                        sortText: "0"
                    });
                }
                for (const pkgName of packageMap.keys()) {
                    acceptor(context, {
                        label: pkgName,
                        insertText: this.astSerializer.serializePrimitive({ value: pkgName }, ID),
                        kind: CompletionItemKind.Module,
                        sortText: "1"
                    });
                }
                for (const [name, types] of byName.entries()) {
                    if (types.length === 1) {
                        acceptor(context, {
                            label: name,
                            insertText: this.astSerializer.serializePrimitive({ value: name }, ID),
                            kind: CompletionItemKind.Class,
                            sortText: "0"
                        });
                    } else {
                        for (const type of types) {
                            const userPkg = findUserVisiblePackage(type.package, packageMap);
                            if (userPkg != undefined) {
                                const qualified = `${userPkg}.${name}`;
                                acceptor(context, {
                                    label: qualified,
                                    insertText: this.astSerializer.serializePrimitive({ value: qualified }, ID),
                                    kind: CompletionItemKind.Class,
                                    sortText: "0"
                                });
                            }
                        }
                    }
                }
            }
        } catch {
            // Ignore errors during completion on incomplete documents
        }
    }

    /**
     * Provides completion items for identifier expressions by querying the Typir scope provider.
     *
     * @param context The current completion context
     * @param acceptor The acceptor function to register completion items
     * @returns `void`
     */
    private completionForIdentifierExpression(context: CompletionContext, acceptor: CompletionAcceptor): void {
        const node = context.node;
        if (node == undefined) {
            return;
        }

        try {
            const boundScope = this.typirScopeProvider.getScope(node);
            const entries = boundScope.getEntries();
            for (const entry of entries) {
                let typeName: string | undefined;
                let kind: CompletionItemKindType = CompletionItemKind.Variable;
                try {
                    const inferredType = entry.inferType();
                    if (!Array.isArray(inferredType)) {
                        typeName = inferredType.getName();
                        if (isCustomFunctionType(inferredType)) {
                            kind = CompletionItemKind.Function;
                        }
                    }
                } catch {
                    // Type inference may fail for some entries
                }

                acceptor(context, {
                    label: entry.name,
                    insertText: this.astSerializer.serializePrimitive({ value: entry.name }, ID),
                    kind,
                    detail: typeName,
                    sortText: "0"
                });
            }
        } catch {
            // Scope resolution may fail on incomplete documents during editing
        }
    }

    /**
     * Provides completion items for member access expressions by inferring the type
     * of the owner expression and listing its properties and methods.
     *
     * The context node may already be the member access/call expression itself (when
     * the AST was partially built) or may be the owner expression. We detect the
     * former by checking both the node's $type and whether the cursor is within the
     * node's source range, and in that case we use the underlying `expression` field
     * (the owner) for type inference. Checking only the type is not sufficient
     * because the same $type can appear in other positions.
     *
     * @param context The current completion context
     * @param acceptor The acceptor function to register completion items
     * @returns `void`
     */
    private completionForMemberAccess(context: CompletionContext, acceptor: CompletionAcceptor): void {
        const node = context.node;
        if (node == undefined) {
            return;
        }

        try {
            let ownerNode = node;

            if (
                this.reflection.isInstance(node, this.expressionTypes.memberAccessExpressionType) ||
                this.reflection.isInstance(node, this.expressionTypes.memberCallExpressionType)
            ) {
                const cst = node.$cstNode;
                const isInsideRange = cst != undefined && cst.offset <= context.offset && context.offset <= cst.end;
                if (isInsideRange) {
                    const underlying = node.expression;
                    if (underlying == undefined) {
                        return;
                    }
                    ownerNode = underlying;
                }
            }

            const ownerType = this.typirInference.inferType(ownerNode);
            if (Array.isArray(ownerType) || !isCustomValueType(ownerType)) {
                return;
            }

            this.addMemberCompletions(ownerType, context, acceptor);
        } catch {
            // Type inference may fail on incomplete documents during editing
        }
    }

    /**
     * Adds completion items for all properties and methods of a type (including inherited ones).
     *
     * @param valueType The resolved owner type whose members are enumerated
     * @param context The current completion context
     * @param acceptor The acceptor function to register completion items
     * @returns `void`
     */
    private addMemberCompletions(
        valueType: CustomValueType,
        context: CompletionContext,
        acceptor: CompletionAcceptor
    ): void {
        const typeArgs = valueType.details.typeArgs;
        for (const prop of valueType.getAllProperties()) {
            acceptor(context, {
                label: prop.name,
                insertText: this.astSerializer.serializePrimitive({ value: prop.name }, ID),
                kind: CompletionItemKind.Property,
                detail: memberValueTypeToString(prop.type, typeArgs),
                sortText: "0"
            });
        }
        for (const method of valueType.getAllMethods()) {
            acceptor(context, {
                label: method.name,
                insertText: this.astSerializer.serializePrimitive({ value: method.name }, ID),
                kind: CompletionItemKind.Method,
                detail: memberMethodDetailString(method, typeArgs),
                sortText: "0"
            });
        }
    }
}

/**
 * Finds the user-visible package name for an internal package string.
 *
 * @param internalPackage The internal package identifier
 * @param packageMap Map from user-visible names to internal packages
 * @returns The user-visible package name, or undefined if not found
 */
function findUserVisiblePackage(internalPackage: string, packageMap: Map<string, string[]>): string | undefined {
    for (const [userPkg, internalPkgs] of packageMap.entries()) {
        if (internalPkgs.includes(internalPackage)) {
            return userPkg;
        }
    }
    return undefined;
}
