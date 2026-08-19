import type { Action, Point } from "@eclipse-glsp/protocol";
import { sharedImport } from "../../sharedImport.js";
import type { Connectable, GModelElement } from "@eclipse-glsp/sprotty";
import {
    StartEdgeReconnectFeedbackAction,
    StopEdgeReconnectFeedbackAction,
    UpdateEdgeReconnectFeedbackAction
} from "./reconnectFeedback.js";
import { SetEdgeEditHighlightAction } from "../create-edge-tool/createEdgeFeedback.js";
import type { EdgeEditTool } from "./edgeEditTool.js";
import type { EdgeAnchor } from "@mdeo/protocol-common";
import { GNode } from "../../model/node.js";
import { GEdge, type ReconnectEnd } from "../../model/edge.js";
import { isConnectable } from "../edge-routing/connectable.js";
import { getRelativePosition } from "../../base/getRelativePosition.js";
import { ReconnectEdgeOperation, UpdateRoutingInformationOperation } from "@mdeo/protocol-common";

const { DragAwareMouseListener, findParentByFeature, isSelected, cursorFeedbackAction } =
    sharedImport("@eclipse-glsp/client");
const { GChildElement } = sharedImport("@eclipse-glsp/sprotty");

/**
 * CSS class for edge reconnect cursor.
 */
const CSS_EDGE_RECONNECT = "edge-reconnect-mode";

/**
 * Mouse listener that provides feedback during edge reconnect operations.
 * Updates the position of the reconnecting end as the mouse moves.
 */
export class FeedbackEdgeReconnectMouseListener extends DragAwareMouseListener {
    /**
     * The edge being edited.
     */
    protected edge?: GEdge;

    /**
     * Which end is being reconnected.
     */
    protected reconnectEnd?: ReconnectEnd;

    /**
     * Whether we're actively tracking a reconnect.
     */
    protected isTracking = false;

    /**
     * The current target node if hovering over a valid target.
     */
    protected currentTarget?: GNode;

    /**
     * The initial target ID when starting the reconnect.
     */
    protected initialTargetId?: string;

    /**
     * The anchor resolved by the latest {@link findReconnectTarget} call.
     * Stored so that same-node drops can dispatch the updated anchor position.
     */
    protected currentAnchor?: EdgeAnchor;

    /**
     * Feedback emitter for cursor changes.
     */
    protected cursorFeedback;

    constructor(protected tool: EdgeEditTool) {
        super();
        this.cursorFeedback = this.tool.createFeedbackEmitter();
    }

    /**
     * Checks if currently tracking a reconnect operation.
     *
     * @returns True if tracking reconnect
     */
    isReconnecting(): boolean {
        return this.isTracking;
    }

    /**
     * Resets the tracking state.
     */
    resetTracking(): void {
        this.edge = undefined;
        this.reconnectEnd = undefined;
        this.currentTarget = undefined;
        this.currentAnchor = undefined;
        this.isTracking = false;
        this.initialTargetId = undefined;
    }

    override mouseDown(target: GModelElement, event: MouseEvent): Action[] {
        const result: Action[] = super.mouseDown(target, event);

        if (event.button != 0) {
            return result;
        }
        if (!(target instanceof GEdge) || !isSelected(target)) {
            return result;
        }

        const eventTarget = event.target;
        if (!(eventTarget instanceof SVGElement)) {
            return result;
        }

        const reconnectHandle = eventTarget.dataset["reconnectHandle"] as ReconnectEnd | undefined;

        if (reconnectHandle != undefined) {
            this.edge = target;
            this.reconnectEnd = reconnectHandle as ReconnectEnd;
            this.isTracking = true;

            const routeResult = this.tool.edgeRouter.computeRoute(target);
            const position =
                reconnectHandle === "source" ? routeResult.route[0] : routeResult.route[routeResult.route.length - 1];
            this.initialTargetId = reconnectHandle === "source" ? target.sourceId : target.targetId;

            result.push(StartEdgeReconnectFeedbackAction.create(target.id, reconnectHandle, position));
            this.cursorFeedback.add(cursorFeedbackAction(CSS_EDGE_RECONNECT), cursorFeedbackAction()).submit();
        }

        return result;
    }

    override draggingMouseMove(target: GModelElement, event: MouseEvent): Action[] {
        super.draggingMouseMove(target, event);

        if (!this.isTracking || !this.edge) {
            return [];
        }

        const elementAtPoint = document.elementFromPoint(event.clientX, event.clientY);
        let actualTarget: GModelElement | undefined;
        if (elementAtPoint && target.root) {
            actualTarget = this.tool.elementFinder.findElementByDOMElement(target.root, elementAtPoint);
        }

        if (!actualTarget) {
            actualTarget = target;
        }

        let position: Point;
        if (this.initialTargetId != undefined) {
            const initialTarget = target.root.index.getById(this.initialTargetId);
            let relativeTarget: GModelElement;
            if (initialTarget != undefined) {
                relativeTarget = initialTarget instanceof GChildElement ? initialTarget.parent : initialTarget;
            } else {
                relativeTarget = target;
            }
            position = getRelativePosition(relativeTarget, event);
        } else {
            position = getRelativePosition(actualTarget, event);
        }

        const targetInfo = this.findReconnectTarget(actualTarget, position);

        let actions: Action[];
        if (targetInfo) {
            actions = [
                UpdateEdgeReconnectFeedbackAction.create(
                    this.edge.id,
                    undefined,
                    targetInfo.anchor,
                    targetInfo.targetId
                ),
                SetEdgeEditHighlightAction.create(targetInfo.targetId, { type: "reconnect" })
            ];
            this.currentTarget = targetInfo.target;
            this.currentAnchor = targetInfo.anchor;
        } else {
            actions = [
                UpdateEdgeReconnectFeedbackAction.create(this.edge.id, position, undefined, undefined),
                SetEdgeEditHighlightAction.create(undefined, undefined)
            ];
            this.currentTarget = undefined;
            this.currentAnchor = undefined;
        }

        return actions;
    }

    override mouseUp(target: GModelElement, event: MouseEvent): Action[] {
        const result = super.mouseUp(target, event);

        if (!this.isTracking || !this.edge) {
            return result;
        }

        const edge = this.edge;

        if (this.currentTarget != undefined && this.currentTarget.id !== this.initialTargetId) {
            // Target changed — dispatch a full reconnect operation.
            const route = this.tool.edgeRouter.computeRoute(edge);

            const operation: ReconnectEdgeOperation = {
                kind: ReconnectEdgeOperation.KIND,
                isOperation: true,
                edgeElementId: edge.id,
                sourceElementId: this.reconnectEnd === "source" ? this.currentTarget.id : edge.sourceId,
                targetElementId: this.reconnectEnd === "target" ? this.currentTarget.id : edge.targetId,
                routingPoints: route.meta.routingPoints,
                sourceAnchor: route.sourceAnchor,
                targetAnchor: route.targetAnchor
            };
            result.push(operation);
        } else if (this.currentTarget != undefined && this.currentAnchor != undefined) {
            // Dropped on the same node but at a (potentially) different anchor position.
            // edge.reconnectData is still populated from the last UpdateEdgeReconnectFeedbackAction,
            // so computeRoute() delegates to computeReconnectRoute which properly handles L-bends
            // and anchor projection — identical to how a normal reconnect computes its route.
            // We do NOT dispatch StopEdgeReconnectFeedbackAction here: doing so would clear
            // reconnectData synchronously, snapping the edge back to the old stored route before
            // the server model update arrives (causing a flicker). The incoming model update from
            // the server will replace the edge with the new meta, clearing the feedback naturally.
            const route = this.tool.edgeRouter.computeRoute(edge);
            const update = {
                elementId: edge.id,
                routingPoints: route.meta.routingPoints ?? [],
                sourceAnchor: route.meta.sourceAnchor,
                targetAnchor: route.meta.targetAnchor
            };
            const operation: UpdateRoutingInformationOperation = {
                kind: UpdateRoutingInformationOperation.KIND,
                isOperation: true,
                updates: [update]
            };
            result.push(operation);
        } else {
            result.push(StopEdgeReconnectFeedbackAction.create(edge.id));
        }

        result.push(SetEdgeEditHighlightAction.create(undefined, undefined));
        this.resetTracking();
        this.cursorFeedback.add(cursorFeedbackAction(), cursorFeedbackAction()).submit();

        return result;
    }

    /**
     * Finds a valid reconnect target at the given position.
     *
     * @param target The model element
     * @param position The mouse position
     * @returns The target info with node, ID, and anchor, or undefined
     */
    protected findReconnectTarget(
        target: GModelElement,
        position: Point
    ): { target: GNode; targetId: string; anchor: EdgeAnchor } | undefined {
        const element = findParentByFeature(
            target,
            (element): element is Connectable & GModelElement =>
                isConnectable(element) && element.canConnect(this.edge!, this.reconnectEnd!)
        );
        if (element == undefined || !(element instanceof GNode)) {
            return undefined;
        }

        if (!this.edge) {
            return undefined;
        }

        const edge = this.edge;

        const targetBounds = element.bounds;
        const anchorProjection = this.tool.edgeRouter.projectAnchor(edge, position, targetBounds);

        return {
            target: element,
            targetId: element.id,
            anchor: anchorProjection.anchor
        };
    }

    override dispose(): void {
        if (this.isTracking) {
            this.tool.dispatchActions([SetEdgeEditHighlightAction.create(undefined, undefined)]);
        }
        this.resetTracking();
        this.cursorFeedback.dispose();
        super.dispose();
    }
}
