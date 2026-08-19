import type { Dimension, GModelElement } from "@eclipse-glsp/sprotty";
import { sharedImport } from "../../sharedImport.js";

const { injectable } = sharedImport("inversify");
const { ChangeBoundsManager: GLSPChangeBoundsManager } = sharedImport("@eclipse-glsp/client");

/**
 * An element that knows a smallest size of its own.
 *
 * The default minimum of the change bounds tool is what the element declares in its layout
 * options, which is a fixed number written down once. A node whose size follows what it shows -
 * a container drawn around a graph, a box around a list - only knows its minimum once its
 * content is measured, and can implement this interface to be asked for it.
 */
export interface MinimumSizeAware {
    /**
     * Returns the smallest size the element may be resized to, in the coordinate system its
     * bounds are given in.
     */
    getMinimumSize(): Dimension;
}

/**
 * Checks whether an element knows a minimum size of its own.
 *
 * @param element The element to check
 * @returns `true` when the element implements {@link MinimumSizeAware}
 */
export function isMinimumSizeAware(element: GModelElement): element is GModelElement & MinimumSizeAware {
    return typeof (element as Partial<MinimumSizeAware>).getMinimumSize === "function";
}

/**
 * Change bounds manager that lets an element state a minimum size of its own.
 *
 * The declared minimum still applies, so an element that implements {@link MinimumSizeAware}
 * can only ever raise the floor, never lower it.
 */
@injectable()
export class ChangeBoundsManager extends GLSPChangeBoundsManager {
    override getMinimumSize(element: GModelElement): Dimension {
        const minimum = super.getMinimumSize(element);
        if (!isMinimumSizeAware(element)) {
            return minimum;
        }
        const own = element.getMinimumSize();
        return {
            width: Math.max(minimum.width, own.width),
            height: Math.max(minimum.height, own.height)
        };
    }
}
