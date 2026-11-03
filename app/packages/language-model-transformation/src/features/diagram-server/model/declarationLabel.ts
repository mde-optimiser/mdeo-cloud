import { GLabel, GLabelBuilder } from "@mdeo/language-shared";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";

/**
 * A row of the start node (an `in` declaration) or of a successful end node (an `out`
 * declaration): the declaration as written, e.g. `in maxLoad: int = 3`. Editing it replaces the
 * header line; deleting it removes the line.
 */
export class GDeclarationLabel extends GLabel {
    /**
     * Creates a builder for GDeclarationLabel instances.
     *
     * @returns A new GDeclarationLabelBuilder
     */
    static builder(): GDeclarationLabelBuilder {
        return new GDeclarationLabelBuilder(GDeclarationLabel).type(ModelTransformationElementType.LABEL_DECLARATION);
    }
}

/**
 * Builder for GDeclarationLabel instances.
 */
export class GDeclarationLabelBuilder<T extends GDeclarationLabel = GDeclarationLabel> extends GLabelBuilder<T> {}
