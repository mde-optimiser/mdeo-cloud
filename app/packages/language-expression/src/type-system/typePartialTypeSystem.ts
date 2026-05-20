import type { TypirLangiumSpecifics } from "typir-langium";
import { PartialTypeSystem } from "./partialTypeSystem.js";
import type { TypeTypes, ClassTypeType } from "../grammar/typeTypes.js";
import type { ExpressionTypirServices } from "./services.js";
import type { CustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import { isCustomValueType } from "../typir-extensions/kinds/custom-value/custom-value-type.js";
import type { CustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import { isCustomVoidType } from "../typir-extensions/kinds/custom-void/custom-void-type.js";
import type { CustomClassType } from "../typir-extensions/kinds/custom-class/custom-class-type.js";
import type { ClassType } from "../typir-extensions/config/type.js";
import { sharedImport } from "@mdeo/language-shared";

const { AstUtils } = sharedImport("langium");

/**
 * Result of resolving a class type from a type reference.
 */
type ClassTypeResolutionResult =
    | { kind: "found"; classType: ClassType }
    | { kind: "not-found"; typeName: string }
    | { kind: "ambiguous"; typeName: string; matchingPackages: string[] }
    | { kind: "invalid-package"; packageName: string; typeName: string };

/**
 * Partial type system implementation for type-related AST nodes.
 * This class handles type inference and validation rules for type declarations,
 * including class types with generics, lambda types, and void types.
 *
 * @template Specifics The language-specific type system configuration extending TypirLangiumSpecifics
 */
export class TypePartialTypeSystem<Specifics extends TypirLangiumSpecifics> extends PartialTypeSystem<
    Specifics,
    TypeTypes
> {
    /**
     * Creates a new TypePartialTypeSystem.
     *
     * @param typir The Typir services
     * @param types The type types for the grammar
     * @param nullableAny The nullable any type for fallback type arguments
     */
    constructor(
        typir: ExpressionTypirServices<Specifics>,
        types: TypeTypes,
        protected readonly nullableAny: CustomClassType
    ) {
        super(typir, types);
    }

    override registerRules(): void {
        this.registerClassTypeInferenceRule();
        this.registerClassTypeValidationRule();
        this.registerVoidTypeInferenceRule();
        this.registerLambdaTypeInferenceRule();
        this.registerLambdaTypeValidationRule();
    }

    /**
     * Resolves a class type from a type reference node.
     * Handles both qualified (package.name) and unqualified (name only) type references.
     *
     * For qualified references, the package name is looked up in the packageMap to find
     * the list of internal packages to search. If multiple types are found across different
     * internal packages, an ambiguous error is returned.
     *
     * @param node The class type AST node
     * @returns The resolution result indicating found type, not found, ambiguous, or invalid package
     */
    private resolveClassType(node: ClassTypeType): ClassTypeResolutionResult {
        const document = AstUtils.getDocument(node);
        const { packageMap, allInternalPackages } = this.typir.PackageMapCache.getDocumentPackageCache(document);
        const typeName = node.name;
        const packageName = node.packageName;

        if (packageName != undefined) {
            const internalPackages = packageMap.get(packageName);

            if (internalPackages == undefined) {
                const classType = this.typir.TypeDefinitions.getClassTypeIfExisting(typeName, packageName);
                if (classType == undefined) {
                    return { kind: "invalid-package", packageName, typeName };
                }
                return { kind: "found", classType };
            }

            const matchingTypes: ClassType[] = [];
            for (const internalPkg of internalPackages) {
                const classType = this.typir.TypeDefinitions.getClassTypeIfExisting(typeName, internalPkg);
                if (classType != undefined) {
                    matchingTypes.push(classType);
                }
            }

            if (matchingTypes.length === 0) {
                return { kind: "not-found", typeName: `${packageName}.${typeName}` };
            }

            if (matchingTypes.length === 1) {
                return { kind: "found", classType: matchingTypes[0] };
            }

            return {
                kind: "ambiguous",
                typeName,
                matchingPackages: matchingTypes.map((t) => t.package)
            };
        }

        const allTypesWithName = this.typir.TypeDefinitions.getClassTypesByName(typeName);
        if (allTypesWithName.length === 0) {
            return { kind: "not-found", typeName };
        }

        const relevantTypes = allTypesWithName.filter((t) => allInternalPackages.has(t.package));

        if (relevantTypes.length === 0) {
            return { kind: "not-found", typeName };
        }

        if (relevantTypes.length === 1) {
            return { kind: "found", classType: relevantTypes[0] };
        }

        return {
            kind: "ambiguous",
            typeName,
            matchingPackages: relevantTypes.map((t) => t.package)
        };
    }

    /**
     * Registers type inference rule for class type declarations.
     * Handles type resolution with generic type arguments.
     */
    private registerClassTypeInferenceRule(): void {
        this.registerInferenceRule(this.types.classTypeType, (node) => {
            const providedTypeArgs = node.typeArgs;
            const resolutionResult = this.resolveClassType(node);

            if (resolutionResult.kind === "not-found") {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Type '${resolutionResult.typeName}' is not defined.`,
                    subProblems: []
                };
            }

            if (resolutionResult.kind === "invalid-package") {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Package '${resolutionResult.packageName}' is not a valid package for type references.`,
                    subProblems: []
                };
            }

            if (resolutionResult.kind === "ambiguous") {
                const packagesStr = resolutionResult.matchingPackages.join(", ");
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Type '${resolutionResult.typeName}' is ambiguous. Found in packages: ${packagesStr}. Use a qualified type reference (e.g., 'package.${resolutionResult.typeName}').`,
                    subProblems: []
                };
            }

            const classTypeDef = resolutionResult.classType;

            if (classTypeDef.isVirtual === true) {
                const fullTypeName = `${classTypeDef.package}.${classTypeDef.name}`;
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Type '${fullTypeName}' is a virtual type and cannot be used as a type annotation.`,
                    subProblems: []
                };
            }

            const expectedGenericCount = classTypeDef.generics?.length ?? 0;
            const providedGenericCount = providedTypeArgs.length;

            const inferredTypeArgs: CustomValueType[] = [];
            for (let i = 0; i < Math.min(expectedGenericCount, providedGenericCount); i++) {
                const typeArg = providedTypeArgs[i];
                const inferredType = this.inference.inferType(typeArg);
                if (Array.isArray(inferredType)) {
                    inferredTypeArgs.push(this.nullableAny);
                } else if (isCustomValueType(inferredType)) {
                    inferredTypeArgs.push(inferredType);
                } else {
                    inferredTypeArgs.push(this.nullableAny);
                }
            }

            for (let i = providedGenericCount; i < expectedGenericCount; i++) {
                inferredTypeArgs.push(this.nullableAny);
            }

            const typeArgMap = new Map<string, CustomValueType>();
            if (classTypeDef.generics) {
                for (let i = 0; i < expectedGenericCount; i++) {
                    const genericName = classTypeDef.generics[i];
                    typeArgMap.set(genericName, inferredTypeArgs[i]);
                }
            }

            return this.typir.factory.CustomClasses.getOrCreate({
                definition: classTypeDef,
                isNullable: node.isNullable,
                typeArgs: typeArgMap
            });
        });
    }

    /**
     * Registers validation rule for class type declarations.
     * Validates generic parameter count and type resolution.
     */
    private registerClassTypeValidationRule(): void {
        this.registerValidationRule(this.types.classTypeType, (node, accept) => {
            const providedTypeArgs = node.typeArgs;
            const resolutionResult = this.resolveClassType(node);

            if (resolutionResult.kind === "not-found") {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Type '${resolutionResult.typeName}' is not defined.`,
                    subProblems: []
                });
                return;
            }

            if (resolutionResult.kind === "invalid-package") {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Package '${resolutionResult.packageName}' is not a valid package for type references.`,
                    subProblems: []
                });
                return;
            }

            if (resolutionResult.kind === "ambiguous") {
                const packagesStr = resolutionResult.matchingPackages.join(", ");
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Type '${resolutionResult.typeName}' is ambiguous. Found in packages: ${packagesStr}. Use a qualified type reference (e.g., 'package.${resolutionResult.typeName}').`,
                    subProblems: []
                });
                return;
            }

            const classTypeDef = resolutionResult.classType;
            const fullTypeName = `${classTypeDef.package}.${classTypeDef.name}`;
            const expectedGenericCount = classTypeDef.generics?.length ?? 0;
            const providedGenericCount = providedTypeArgs.length;

            if (providedGenericCount > expectedGenericCount) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Type '${fullTypeName}' expects ${expectedGenericCount} generic parameter(s), but ${providedGenericCount} were provided.`,
                    subProblems: []
                });
            }

            if (providedGenericCount < expectedGenericCount) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: node,
                    message: `Type '${fullTypeName}' expects ${expectedGenericCount} generic parameter(s), but only ${providedGenericCount} were provided.`,
                    subProblems: []
                });
            }
        });
    }

    /**
     * Registers type inference rule for void type declarations.
     * Returns the void type singleton.
     */
    private registerVoidTypeInferenceRule(): void {
        this.registerInferenceRule(this.types.voidTypeType, () => {
            return this.typir.factory.CustomVoid.getOrCreate();
        });
    }

    /**
     * Registers type inference rule for lambda type declarations.
     * Handles lambda types with parameter types and return types.
     */
    private registerLambdaTypeInferenceRule(): void {
        this.registerInferenceRule(this.types.lambdaTypeType, (node) => {
            const parameterTypes: CustomValueType[] = [];

            for (let i = 0; i < node.parameterList.parameters.length; i++) {
                const param = node.parameterList.parameters[i];
                const inferredType = this.inference.inferType(param);
                if (Array.isArray(inferredType)) {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node,
                        location: `Failed to infer parameter type.`,
                        subProblems: []
                    };
                }
                if (!isCustomValueType(inferredType)) {
                    return {
                        $problem: this.inferenceProblem,
                        languageNode: node,
                        location: `Parameter type must be a value type.`,
                        subProblems: []
                    };
                }
                parameterTypes.push(inferredType);
            }

            const returnTypeNode = node.returnType;
            const inferredReturnType = this.inference.inferType(returnTypeNode);

            if (Array.isArray(inferredReturnType)) {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Failed to infer return type.`,
                    subProblems: []
                };
            }

            let returnType: CustomValueType | CustomVoidType;

            if (isCustomVoidType(inferredReturnType) || isCustomValueType(inferredReturnType)) {
                returnType = inferredReturnType;
            } else {
                return {
                    $problem: this.inferenceProblem,
                    languageNode: node,
                    location: `Return type must be a value type or void.`,
                    subProblems: []
                };
            }

            return this.typir.factory.CustomLambdas.getOrCreate({
                returnType,
                parameterTypes,
                typeArgs: new Map(),
                isNullable: node.isNullable
            });
        });
    }

    /**
     * Registers validation rule for lambda type declarations.
     * Validates that parameter and return types can be inferred.
     */
    private registerLambdaTypeValidationRule(): void {
        this.registerValidationRule(this.types.lambdaTypeType, (node, accept) => {
            for (const param of node.parameterList.parameters) {
                const inferredType = this.inference.inferType(param);
                if (Array.isArray(inferredType)) {
                    accept({
                        $problem: this.validationProblem,
                        severity: "error",
                        languageNode: param,
                        message: `Failed to infer parameter type.`,
                        subProblems: []
                    });
                }
            }

            const returnTypeNode = node.returnType;
            const inferredReturnType = this.inference.inferType(returnTypeNode);
            if (Array.isArray(inferredReturnType)) {
                accept({
                    $problem: this.validationProblem,
                    severity: "error",
                    languageNode: returnTypeNode,
                    message: `Failed to infer return type.`,
                    subProblems: []
                });
            }
        });
    }
}
