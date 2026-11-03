import { GLabel, GNode, nodeLayoutMetadataFeature, sharedImport } from "@mdeo/editor-shared";

const { boundsFeature, selectFeature, deletableFeature, moveFeature, connectableFeature } =
    sharedImport("@eclipse-glsp/sprotty");

/**
 * Client-side model for call nodes: a statement shown as its text — a call of another
 * transformation, the head of a `for … in` loop or an assignment outside a pattern.
 */
export class GCallNode extends GNode {
    static readonly DEFAULT_FEATURES = [
        boundsFeature,
        selectFeature,
        deletableFeature,
        moveFeature,
        connectableFeature,
        nodeLayoutMetadataFeature
    ];

    constructor() {
        super();
        this.vAlign = "center";
        this.hAlign = "center";
    }

    /**
     * The statement as written, e.g. `call moveComponent(maxCandidates = 2) -> (moved: m)`.
     */
    text!: string;

    /**
     * What the node stands for: `call`, `loop` (a `for … in` head) or `assignment`.
     */
    variant?: "call" | "loop" | "assignment";
}

/**
 * Client-side model for the editable text of a call node.
 */
export class GCallLabel extends GLabel {}
