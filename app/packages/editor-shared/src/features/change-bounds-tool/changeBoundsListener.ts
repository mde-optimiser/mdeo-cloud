import type {
    ResizeHandleLocation as ResizeHandleLocationType,
    GResizeHandle as GResizeHandleType,
    SelectableBoundsAware,
    TrackedResize,
    GModelElement
} from "@eclipse-glsp/client";
import type { Action, Operation } from "@eclipse-glsp/sprotty";
import type { PartialElementAndBounds, EdgeRoutingUpdate } from "@mdeo/protocol-common";
import type { ExtendedSetBoundsFeedbackAction } from "../metadata/setBoundsFeedbackCommand.js";
import { sharedImport } from "../../sharedImport.js";
import { GNode } from "../../model/node.js";
import type { GEdge } from "../../model/edge.js";
import type { ChangeBoundsTool } from "./changeBoundsTool.js";
import { PartialChangeBoundsOperation, UpdateRoutingInformationOperation } from "@mdeo/protocol-common";

const {
    ChangeBoundsListener: GLSPChangeBoundsListener,
    GResizeHandle,
    Point,
    Dimension,
    ResizeHandleLocation
} = sharedImport("@eclipse-glsp/client");

/**
 * Custom change bounds listener that handles resize operations with custom SVG-based resize handles.
 * Interprets resize handle locations from SVG data attributes to support invisible resize handles.
 */

export class ChangeBoundsListener extends GLSPChangeBoundsListener {
    /**
     * Skips all change-bounds tracking when the alt key is held.  Alt+click is reserved
     * for the reveal-source action; without this guard the drag-tracking state machine
     * in {@link DragAwareMouseListener} is left in a stale "mouse-down" state after focus
     * moves to the newly opened tab, which causes a spurious move/resize once the user
     * returns to the diagram.
     */
    override mouseDown(target: GModelElement, event: MouseEvent): Action[] {
        if (event.altKey) {
            return [];
        }
        return super.mouseDown(target, event);
    }

    /**
     * Updates the resize element by extracting resize handle location from SVG data attributes.
     * Creates a virtual GResizeHandle when a resize handle location is detected in the event target.
     *
     * @param target The target element to resize
     * @param event The mouse event that triggered the resize
     * @returns True if the resize element was updated successfully
     */
    protected override updateResizeElement(target: GModelElement, event?: MouseEvent): boolean {
        let actualTarget = target;
        const eventTarget = event?.target;
        if (eventTarget instanceof SVGElement) {
            const location = eventTarget.dataset["resizeHandleLocation"];
            if (typeof location === "string") {
                const resizeHandle = new GResizeHandle(location as ResizeHandleLocationType);
                // @ts-expect-error not readonly
                resizeHandle.parent = target;
                actualTarget = resizeHandle;
            }
        }
        return super.updateResizeElement(actualTarget, event);
    }

    protected override handleResizeOnServer(activeResizeHandle: GResizeHandleType): Action[] {
        const resizedElement = activeResizeHandle.parent;
        if (this.initialBounds && this.isValidResize(resizedElement)) {
            const location = activeResizeHandle.location;
            const elementAndBounds: PartialElementAndBounds = {
                elementId: resizedElement.id,
                newPosition: {
                    x: resizedElement.bounds.x,
                    y: resizedElement.bounds.y
                },
                newSize: {
                    width:
                        location !== ResizeHandleLocation.Top && location !== ResizeHandleLocation.Bottom
                            ? resizedElement.bounds.width
                            : undefined,
                    height:
                        location !== ResizeHandleLocation.Left && location !== ResizeHandleLocation.Right
                            ? resizedElement.bounds.height
                            : undefined
                }
            };
            if (!this.initialBounds.newPosition || !elementAndBounds.newPosition) {
                return [];
            }
            if (
                !Point.equals(this.initialBounds.newPosition, elementAndBounds.newPosition) ||
                !Dimension.equals(this.initialBounds.newSize, resizedElement.bounds)
            ) {
                this.initialBounds = undefined;
                return [this.createPartialChangeBoundsOperation([elementAndBounds])];
            }
        }
        return [];
    }

    protected override handleMoveElementsOnServer(elementsToMove: SelectableBoundsAware[]): Operation[] {
        const newBounds = elementsToMove.map((element) => ({
            elementId: element.id,
            newPosition: {
                x: element.bounds.x,
                y: element.bounds.y
            }
        }));
        return newBounds.length > 0 ? [this.createPartialChangeBoundsOperation(newBounds)] : [];
    }

    protected override resizeBoundsAction(resize: TrackedResize): ExtendedSetBoundsFeedbackAction {
        const location = this.activeResizeHandle?.location;
        return {
            ...super.resizeBoundsAction(resize),
            setPrefWidth: location !== ResizeHandleLocation.Top && location !== ResizeHandleLocation.Bottom,
            setPrefHeight: location !== ResizeHandleLocation.Left && location !== ResizeHandleLocation.Right
        };
    }

    protected override handleMoveRoutingPointsOnServer(elementsToMove: SelectableBoundsAware[]): Operation[] {
        if (elementsToMove.length === 0) {
            return [];
        }
        const updates: EdgeRoutingUpdate[] = [];
        const edges = new Set<GEdge>();
        for (const element of elementsToMove) {
            if (element instanceof GNode) {
                element.incomingEdges().forEach((e) => edges.add(e));
                element.outgoingEdges().forEach((e) => edges.add(e));
            }
        }
        for (const edge of edges) {
            const meta = (this.tool as ChangeBoundsTool).edgeRouter.computeRoute(edge).meta;
            updates.push({
                elementId: edge.id,
                routingPoints: meta.routingPoints,
                sourceAnchor: meta.sourceAnchor,
                targetAnchor: meta.targetAnchor
            });
        }
        const operation: UpdateRoutingInformationOperation = {
            kind: UpdateRoutingInformationOperation.KIND,
            isOperation: true,
            updates
        };
        return [operation];
    }

    /**
     * Creates a PartialChangeBoundsOperation for the given element and bounds.
     *
     * @param elementAndBounds The partial element and bounds to include in the operation
     * @returns The created PartialChangeBoundsOperation
     */
    private createPartialChangeBoundsOperation(
        elementAndBounds: PartialElementAndBounds[]
    ): PartialChangeBoundsOperation {
        return {
            kind: PartialChangeBoundsOperation.KIND,
            newBounds: elementAndBounds,
            isOperation: true
        };
    }
}
