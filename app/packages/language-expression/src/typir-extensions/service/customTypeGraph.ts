import { sharedImport } from "@mdeo/language-shared";
import type { Type, TypeEdge, TypeGraph as TypeGraphType } from "typir";

const { TypeGraph } = sharedImport("typir");

/**
 * Edge relation for type dependencies.
 */
export const DependsOnTypeEdgeRelation = "depends_on_type";

/**
 * Type edge representing a type dependency.
 */
export interface DependsOnTypeEdge extends TypeEdge {
    readonly $relation: typeof DependsOnTypeEdgeRelation;
}

/**
 * Type graph implementation that removes dependent types together with a removed type.
 */
export class CustomTypeGraph extends TypeGraph {
    /**
     * Removes a type and all transitive dependents linked with depends_on_type edges.
     *
     * @param typeToRemove The root type to remove
     * @param key Optional graph key used for this type
     */
    override removeNode(typeToRemove: Type, key?: string): void {
        const mapKey = key ?? typeToRemove.getIdentifier();
        if (this.getNode(mapKey) === undefined) {
            return;
        }

        const dependentsToRemove = this.collectDependents(typeToRemove);
        for (const dependent of dependentsToRemove) {
            const dependentKey = dependent.getIdentifier();
            if (this.getNode(dependentKey) !== undefined) {
                this.removeNode(dependent);
            }
        }

        super.removeNode(typeToRemove, key);
    }

    /**
     * Collects all transitive dependents of a type.
     *
     * @param dependencyType The dependency type
     * @returns Set of dependent types
     */
    private collectDependents(dependencyType: Type): Set<Type> {
        const result = new Set<Type>();
        const stack: Type[] = [dependencyType];

        while (stack.length > 0) {
            const currentDependency = stack.pop()!;
            const incomingEdges = currentDependency.getIncomingEdges<DependsOnTypeEdge>(DependsOnTypeEdgeRelation);

            for (const edge of incomingEdges) {
                const dependentType = edge.from;
                if (result.has(dependentType)) {
                    continue;
                }
                if (this.getNode(dependentType.getIdentifier()) === undefined) {
                    continue;
                }
                result.add(dependentType);
                stack.push(dependentType);
            }
        }

        return result;
    }
}

/**
 * Registers a depends_on_type edge between two types if needed.
 *
 * @param graph The type graph
 * @param dependentType The type that depends on another type
 * @param dependencyType The depended-on type
 */
export function addDependsOnTypeEdge(graph: TypeGraphType, dependentType: Type, dependencyType: Type): void {
    if (dependentType === dependencyType) {
        return;
    }
    if (graph.getNode(dependentType.getIdentifier()) === undefined) {
        return;
    }
    if (graph.getNode(dependencyType.getIdentifier()) === undefined) {
        return;
    }
    if (graph.getUnidirectionalEdge(dependentType, dependencyType, DependsOnTypeEdgeRelation) !== undefined) {
        return;
    }

    graph.addEdge({
        $relation: DependsOnTypeEdgeRelation,
        from: dependentType,
        to: dependencyType,
        cachingInformation: "LINK_EXISTS"
    });
}
