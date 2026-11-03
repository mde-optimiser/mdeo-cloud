import { sharedImport } from "@mdeo/language-shared";
import type { LangiumTypeCreator, TypirLangiumServices, TypirLangiumSpecifics } from "typir-langium";
import type { ExtendedTypirServices } from "../service/extendedTypirServices.js";
import type { ClassType, ClassTypeRef } from "../config/type.js";
import type { TypeDefinitionListener, TypeDefinitionService } from "../service/typeDefinitionService.js";
import type { Type } from "typir";
import { isCustomClassType, type CustomClassType } from "../kinds/custom-class/custom-class-type.js";

const { DefaultLangiumTypeCreator } = sharedImport("typir-langium");

/**
 * Generates a TypeCreator class that integrates with the TypeDefinitionService
 * to manage class type definitions based on document lifecycle events.
 *
 * @returns An object containing the TypeCreator class provider
 */
export function generateTypeCreator<Specifics extends TypirLangiumSpecifics>(): {
    TypeCreator: (services: ExtendedTypirServices<Specifics> & TypirLangiumServices<Specifics>) => LangiumTypeCreator;
} {
    class ExtendedTypeCreator extends DefaultLangiumTypeCreator<Specifics> implements TypeDefinitionListener {
        private readonly documentTypeDefinitionMap: Map<string, ClassType[]> = new Map();
        private readonly typeDefinition: TypeDefinitionService;

        /**
         * Map of ClassTypes to its instances (CustomClassType)
         *
         * Outer map key: package name
         * Inner map key: class name
         */
        private readonly typeMap = new Map<string, Map<string, CustomClassType[]>>();

        constructor(services: ExtendedTypirServices<Specifics> & TypirLangiumServices<Specifics>) {
            super(services);
            this.typeDefinition = services.TypeDefinitions;
            this.typeDefinition.addListener(this);
        }

        protected override invalidateTypesOfDocument(documentKey: string): void {
            super.invalidateTypesOfDocument(documentKey);
            const removedDefinitions = this.documentTypeDefinitionMap.get(documentKey) ?? [];
            removedDefinitions.forEach((classTypeToRemove) => this.typeDefinition.removeClassType(classTypeToRemove));
            this.documentTypeDefinitionMap.delete(documentKey);
            if (removedDefinitions.length > 0) {
                // The class types of a metamodel are registered by the first document that imports
                // it, but every other document importing the same metamodel uses them too, and its
                // cached inferences (and those of the metamodel's own nodes) still point to the
                // instances just removed from the graph. Such a type is assignable to nothing, not
                // even to the fresh instance of the same class, so a call argument typed in one file
                // would no longer match the parameter of another. Typir clears its cache the same
                // way when an inference rule is removed.
                this.typir.caching.LanguageNodeInference.cacheClear();
            }
        }

        override onAddedType(newType: Type): void {
            super.onAddedType(newType);

            if (isCustomClassType(newType)) {
                const definition = newType.definition as ClassTypeRef;
                const nameMap = this.typeMap.get(definition.type);
                if (nameMap == undefined) {
                    this.typeMap.set(definition.type, new Map([[definition.package, [newType]]]));
                } else {
                    const packageList = nameMap.get(definition.package);
                    if (packageList == undefined) {
                        nameMap.set(definition.package, [newType]);
                    } else {
                        packageList.push(newType);
                    }
                }
            }
        }

        onAddedClassType(classType: ClassType): void {
            if (this.currentDocumentKey != undefined) {
                let types = this.documentTypeDefinitionMap.get(this.currentDocumentKey);
                if (types == undefined) {
                    types = [];
                    this.documentTypeDefinitionMap.set(this.currentDocumentKey, types);
                }
                types.push(classType);
            }
        }

        onRemovedClassType(classType: ClassType): void {
            const nameMap = this.typeMap.get(classType.name);
            if (nameMap == undefined) {
                return;
            }
            const instanceList = nameMap.get(classType.package);
            if (instanceList == undefined) {
                return;
            }
            for (const instance of instanceList) {
                this.typeGraph.removeNode(instance);
            }
            nameMap.delete(classType.package);

            if (nameMap.size === 0) {
                this.typeMap.delete(classType.name);
            }
        }
    }

    return {
        TypeCreator: (services) => new ExtendedTypeCreator(services)
    };
}
