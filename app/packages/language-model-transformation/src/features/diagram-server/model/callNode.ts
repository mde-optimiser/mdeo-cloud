import { GLabel, GLabelBuilder, GNode, GNodeBuilder } from "@mdeo/language-shared";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";

/**
 * What a call node stands for:
 * - `call`: a call of another transformation (`call …` or the call of an `if call`), drawn with
 *   a double left edge, its text editable in the diagram;
 * - `loop`: the head of a `for … in` loop;
 * - `assignment`: an assignment outside a pattern (`x = …`, `x += …`).
 */
export type CallNodeVariant = "call" | "loop" | "assignment";

/**
 * Server-side node for a statement shown as its text: a call of another transformation
 * (`call name(...) -> (...)`), the head of a `for … in` loop or an assignment outside a
 * pattern. The callee's statements are not part of this diagram.
 */
export class GCallNode extends GNode {
    /**
     * The statement as written, e.g. `call moveComponent(maxCandidates = 2) -> (moved: m)`.
     */
    text!: string;

    /**
     * What the node stands for.
     */
    variant: CallNodeVariant = "call";

    static builder(): GCallNodeBuilder {
        return new GCallNodeBuilder(GCallNode).type(ModelTransformationElementType.NODE_CALL);
    }
}

/**
 * Builder for {@link GCallNode}.
 */
export class GCallNodeBuilder<T extends GCallNode = GCallNode> extends GNodeBuilder<T> {
    text(text: string): this {
        this.proxy.text = text;
        return this;
    }

    variant(variant: CallNodeVariant): this {
        this.proxy.variant = variant;
        return this;
    }
}

/**
 * The editable text of a call node: editing it replaces the call in the source.
 */
export class GCallLabel extends GLabel {
    static builder(): GCallLabelBuilder {
        return new GCallLabelBuilder(GCallLabel).type(ModelTransformationElementType.LABEL_CALL);
    }
}

/**
 * Builder for {@link GCallLabel}.
 */
export class GCallLabelBuilder<T extends GCallLabel = GCallLabel> extends GLabelBuilder<T> {}
