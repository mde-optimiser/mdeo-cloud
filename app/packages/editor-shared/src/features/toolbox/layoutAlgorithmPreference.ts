import type { LayoutAlgorithmKind } from "@mdeo/protocol-common";

/**
 * Description of a layout algorithm offered in the layout panel.
 */
export interface LayoutAlgorithmOption {
    /**
     * The algorithm.
     */
    id: LayoutAlgorithmKind;
    /**
     * The short name shown on the button.
     */
    label: string;
    /**
     * A description, used as tooltip.
     */
    description: string;
}

/**
 * The layout algorithms offered to the user, in display order.
 *
 * The server also implements "force" (ELK force placement plus a simple orthogonal router), but it is not offered:
 * on the test diagrams it produces long, tangled routes with many crossings, and ELK stress ignores the node sizes.
 */
export const LAYOUT_ALGORITHM_OPTIONS: readonly LayoutAlgorithmOption[] = [
    { id: "layered", label: "Layered", description: "Edges flow in one direction (ELK)" },
    { id: "tala", label: "TALA", description: "Compact orthogonal layout (orthoflow)" }
];

/**
 * The algorithm used when the user did not pick one yet.
 */
export const DEFAULT_LAYOUT_ALGORITHM: LayoutAlgorithmKind = "layered";

/**
 * Local storage key prefix of the default layout algorithm (per diagram type).
 */
const STORAGE_KEY_PREFIX = "mdeo.layout.algorithm.";

/**
 * Reads the default layout algorithm of a diagram type.
 *
 * @param diagramType the diagram type
 * @returns the remembered algorithm, {@link DEFAULT_LAYOUT_ALGORITHM} if none (or an unknown one) is remembered
 */
export function readDefaultLayoutAlgorithm(diagramType: string | undefined): LayoutAlgorithmKind {
    try {
        const value = localStorage.getItem(STORAGE_KEY_PREFIX + (diagramType ?? ""));
        if (LAYOUT_ALGORITHM_OPTIONS.some((option) => option.id === value)) {
            return value as LayoutAlgorithmKind;
        }
    } catch {
        // storage may be unavailable
    }
    return DEFAULT_LAYOUT_ALGORITHM;
}

/**
 * Remembers the default layout algorithm of a diagram type.
 *
 * @param diagramType the diagram type
 * @param algorithm the algorithm
 */
export function writeDefaultLayoutAlgorithm(diagramType: string | undefined, algorithm: LayoutAlgorithmKind): void {
    try {
        localStorage.setItem(STORAGE_KEY_PREFIX + (diagramType ?? ""), algorithm);
    } catch {
        // storage may be unavailable; the choice then lasts as long as the editor
    }
}
