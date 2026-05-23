import type { TypirLangiumSpecifics } from "typir-langium";
import type { ReferenceDescription, AstNode, LangiumDocument, CstNode } from "langium";
import type { ExpressionTypirServices } from "../type-system/services.js";
import type {
    ExpressionTypes,
    IdentifierExpressionType,
    MemberAccessExpressionType,
    MemberCallExpressionType
} from "../grammar/expressionTypes.js";
import type { TypeTypes } from "../grammar/typeTypes.js";
import type { ClassTypeType } from "../grammar/typeTypes.js";
import type { StatementTypes } from "../grammar/statementTypes.js";
import type { ScopeProvider } from "../typir-extensions/scope/scopeProvider.js";
import type { ExtendedTypirServices } from "../typir-extensions/service/extendedTypirServices.js";
import { isCustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { sharedImport } from "@mdeo/language-shared";
import type { AstReflection, ExtendedLangiumServices } from "@mdeo/language-common";

const { DefaultReferenceDescriptionProvider, CstUtils, AstUtils, UriUtils, GrammarUtils } = sharedImport("langium");

/**
 * Custom {@link ReferenceDescriptionProvider} that extends Langium's default provider with
 * descriptions for expression-level constructs resolved via Typir's scope and type system.
 *
 * The default Langium provider only tracks standard cross-references (declared in the grammar
 * with `[...]`). This provider additionally emits descriptions for:
 *
 * - **IdentifierExpression** nodes — identifier names resolved via the Typir scope provider,
 *   e.g., function calls and variable accesses. A description is created when the matching
 *   scope entry carries a `languageNode` (the defining AST node).
 * - **MemberAccess / MemberCallExpression** nodes — member names resolved via the Typir type
 *   system. A description is created when the corresponding {@link Property} or {@link Method}
 *   on the inferred owner type carries a `languageNode`.
 * - **ClassType annotation** nodes (when `typeTypes` is provided) — class type names in type
 *   annotations resolved via the Typir type-definition registry. A description is created when
 *   the corresponding {@link ClassType} entry carries a `languageNode`.
 *
 * These descriptions enable LSP rename and find-references to work correctly across language
 * boundaries (e.g., renaming a metamodel class propagates to all expression usages).
 *
 * The `typeTypes` and `statementTypes` parameters are optional so that languages that omit
 * those grammar fragments can still use this provider.
 */
export class ExpressionReferenceDescriptionProvider extends DefaultReferenceDescriptionProvider {
    private readonly typirScopeProvider: ScopeProvider<TypirLangiumSpecifics>;
    private readonly typirInference: ExtendedTypirServices<TypirLangiumSpecifics>["Inference"];
    private readonly typir: ExpressionTypirServices<TypirLangiumSpecifics>;
    protected readonly reflection: AstReflection;

    constructor(
        services: {
            typir: ExpressionTypirServices<TypirLangiumSpecifics>;
        } & ExtendedLangiumServices,
        protected readonly expressionTypes: ExpressionTypes,
        protected readonly typeTypes?: TypeTypes,
        protected readonly statementTypes?: StatementTypes
    ) {
        super(services);
        this.typir = services.typir;
        this.typirScopeProvider = services.typir.ScopeProvider;
        this.typirInference = services.typir.Inference;
        this.reflection = services.shared.AstReflection;
    }

    override async createDescriptions(
        document: LangiumDocument,
        cancelToken?: unknown
    ): Promise<ReferenceDescription[]> {
        const descriptions = await super.createDescriptions(document, cancelToken as never);
        const rootNode = document.parseResult?.value;
        if (rootNode == undefined) {
            return descriptions;
        }

        const nodes = new Set<AstNode>(AstUtils.streamAst(rootNode));

        for (const node of nodes) {
            try {
                if (this.reflection.isInstance(node, this.expressionTypes.identifierExpressionType)) {
                    this.addIdentifierExpressionDescriptions(node as IdentifierExpressionType, descriptions);
                }
                if (this.reflection.isInstance(node, this.expressionTypes.memberAccessExpressionType)) {
                    this.addMemberAccessDescriptions(node as MemberAccessExpressionType, descriptions);
                }
                if (this.reflection.isInstance(node, this.expressionTypes.memberCallExpressionType)) {
                    this.addMemberCallDescriptions(node as MemberCallExpressionType, descriptions);
                }
                if (this.typeTypes != undefined && this.reflection.isInstance(node, this.typeTypes.classTypeType)) {
                    this.addClassTypeAnnotationDescriptions(node as ClassTypeType, document, descriptions);
                }
            } catch {
                // Ignore errors on incomplete/partial AST nodes
            }
        }

        return descriptions;
    }

    /**
     * Creates a reference description for an IdentifierExpression node when the Typir scope
     * entry for the identifier name carries a `languageNode` (e.g., a ScriptFunction or
     * variable declaration node).
     */
    private addIdentifierExpressionDescriptions(
        node: IdentifierExpressionType,
        descriptions: ReferenceDescription[]
    ): void {
        const boundScope = this.typirScopeProvider.getScope(node);
        const entry = boundScope.getEntry(node.name);
        if (entry == undefined || entry.languageNode == undefined) {
            return;
        }
        const targetNode = entry.languageNode as AstNode;
        if (targetNode.$cstNode == undefined) {
            return;
        }
        const nameCstNode = this.findCstNodeForProperty(node, "name");
        if (nameCstNode == undefined) {
            return;
        }

        const sourceDoc = AstUtils.getDocument(node);
        const targetDoc = AstUtils.getDocument(targetNode);
        descriptions.push({
            sourceUri: sourceDoc.uri,
            sourcePath: this.nodeLocator.getAstNodePath(node),
            targetUri: targetDoc.uri,
            targetPath: this.nodeLocator.getAstNodePath(targetNode),
            segment: CstUtils.toDocumentSegment(nameCstNode),
            local: UriUtils.equals(sourceDoc.uri, targetDoc.uri)
        });
    }

    /**
     * Creates a reference description for a MemberAccessExpression when the matching
     * {@link Property} or {@link Method} on the inferred owner type carries a `languageNode`.
     * Properties are preferred over methods for plain member access (field-like access).
     */
    private addMemberAccessDescriptions(node: MemberAccessExpressionType, descriptions: ReferenceDescription[]): void {
        this.addMemberDescription(node, descriptions, /* propertyFirst */ true);
    }

    /**
     * Creates a reference description for a MemberCallExpression when the matching
     * {@link Method} or {@link Property} on the inferred owner type carries a `languageNode`.
     * Methods are preferred over properties for member call (invocation) access.
     */
    private addMemberCallDescriptions(node: MemberCallExpressionType, descriptions: ReferenceDescription[]): void {
        this.addMemberDescription(node, descriptions, /* propertyFirst */ false);
    }

    /**
     * Shared implementation for member access and member call description creation.
     *
     * @param node The member access or member call expression node
     * @param descriptions The array to push the created description into
     * @param propertyFirst When `true`, properties take priority over methods; when `false`, methods take priority
     */
    private addMemberDescription(
        node: MemberAccessExpressionType | MemberCallExpressionType,
        descriptions: ReferenceDescription[],
        propertyFirst: boolean
    ): void {
        if (node.expression == undefined) {
            return;
        }
        const ownerType = this.typirInference.inferType(node.expression);
        if (Array.isArray(ownerType) || !isCustomValueType(ownerType)) {
            return;
        }
        const member = propertyFirst
            ? (ownerType.getProperty(node.member) ?? ownerType.getMethod(node.member))
            : (ownerType.getMethod(node.member) ?? ownerType.getProperty(node.member));
        if (member == undefined || member.languageNode == undefined) {
            return;
        }
        const targetNode = member.languageNode as AstNode;
        if (targetNode.$cstNode == undefined) {
            return;
        }
        const memberCstNode = this.findCstNodeForProperty(node, "member");
        if (memberCstNode == undefined) {
            return;
        }

        const sourceDoc = AstUtils.getDocument(node);
        const targetDoc = AstUtils.getDocument(targetNode);
        descriptions.push({
            sourceUri: sourceDoc.uri,
            sourcePath: this.nodeLocator.getAstNodePath(node),
            targetUri: targetDoc.uri,
            targetPath: this.nodeLocator.getAstNodePath(targetNode),
            segment: CstUtils.toDocumentSegment(memberCstNode),
            local: UriUtils.equals(sourceDoc.uri, targetDoc.uri)
        });
    }

    /**
     * Creates a reference description for a ClassType annotation node when the corresponding
     * {@link ClassType} entry in the Typir type-definition registry carries a `languageNode`.
     *
     * When `packageName` is present, only classes from the matching internal packages are
     * considered. When it is absent, a reference is created only if the class name is
     * unambiguous (exactly one matching class).
     */
    private addClassTypeAnnotationDescriptions(
        node: ClassTypeType,
        document: LangiumDocument,
        descriptions: ReferenceDescription[]
    ): void {
        const { packageMap, allInternalPackages } = this.typir.PackageMapCache.getDocumentPackageCache(document);
        const nameCstNode = this.findCstNodeForProperty(node, "name");
        if (nameCstNode == undefined) {
            return;
        }

        if (node.packageName != undefined) {
            const internalPackages = packageMap.get(node.packageName) ?? [];
            for (const internalPkg of internalPackages) {
                const classTypeDef = this.typir.TypeDefinitions.getClassTypeIfExisting(node.name, internalPkg);
                if (classTypeDef?.languageNode != undefined) {
                    this.pushClassTypeDescription(
                        node,
                        nameCstNode,
                        classTypeDef.languageNode as AstNode,
                        descriptions
                    );
                }
            }
        } else {
            const types = this.typir.TypeDefinitions.getClassTypesByName(node.name).filter((type) =>
                allInternalPackages.has(type.package)
            );
            if (types.length === 1 && types[0].languageNode != undefined) {
                this.pushClassTypeDescription(node, nameCstNode, types[0].languageNode as AstNode, descriptions);
            }
        }
    }

    /**
     * Appends a single {@link ReferenceDescription} entry that maps the given CST segment
     * inside `sourceNode` to the given `targetNode`.
     */
    private pushClassTypeDescription(
        sourceNode: AstNode,
        nameCstNode: CstNode,
        targetNode: AstNode,
        descriptions: ReferenceDescription[]
    ): void {
        if (targetNode.$cstNode == undefined) {
            return;
        }
        const sourceDoc = AstUtils.getDocument(sourceNode);
        const targetDoc = AstUtils.getDocument(targetNode);
        descriptions.push({
            sourceUri: sourceDoc.uri,
            sourcePath: this.nodeLocator.getAstNodePath(sourceNode),
            targetUri: targetDoc.uri,
            targetPath: this.nodeLocator.getAstNodePath(targetNode),
            segment: CstUtils.toDocumentSegment(nameCstNode),
            local: UriUtils.equals(sourceDoc.uri, targetDoc.uri)
        });
    }

    /**
     * Finds the CST leaf node that corresponds to a specific grammar assignment feature
     * within the given AST node's CST subtree.
     *
     * @param node The AST node whose CST subtree is searched
     * @param property The grammar feature name to look for (e.g., `"member"`, `"name"`)
     * @returns The first matching CST node, or `undefined` if not found
     */
    private findCstNodeForProperty(node: AstNode, property: string): CstNode | undefined {
        if (node.$cstNode == undefined) {
            return undefined;
        }
        return GrammarUtils.findNodeForProperty(node.$cstNode, property);
    }
}
