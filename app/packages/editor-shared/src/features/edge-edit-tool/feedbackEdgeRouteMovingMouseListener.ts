import type { Action, Point, GModelElement, BoundsAware } from "@eclipse-glsp/sprotty";
import { sharedImport } from "../../sharedImport.js";
import { GEdge } from "../../model/edge.js";
import type { EdgeRouter } from "../edge-routing/edgeRouter.js";
import { Orientation } from "../edge-routing/edgeRouter.js";
import { SetEdgeRoutingFeedbackAction } from "./edgeRoutingFeedback.js";
import type { EdgeEditTool } from "./edgeEditTool.js";
import type { EdgeLayoutMetadata, EdgeAnchor, AnchorSide } from "@mdeo/protocol-common";
import type { Bounds as BoundsType } from "@eclipse-glsp/protocol";
import { UpdateRoutingInformationOperation } from "@mdeo/protocol-common";

const { DragAwareMouseListener, getAbsolutePosition, cursorFeedbackAction, isSelected, CursorCSS } =
    sharedImport("@eclipse-glsp/client");
const { findParentByFeature, isViewport, translatePoint } = sharedImport("@eclipse-glsp/sprotty");
const { Point: PointUtil } = sharedImport("@eclipse-glsp/protocol");

/**
 * Mouse listener that handles edge route segment dragging.
 * Provides visual feedback while dragging and computes updated routing information.
 */
export class FeedbackEdgeRouteMovingMouseListener extends DragAwareMouseListener {
    /**
     * The edge being edited.
     */
    protected edge?: GEdge;

    /**
     * The segment index being edited.
     */
    protected segmentIndex?: number;

    /**
     * The initial metadata when dragging started.
     */
    protected startMeta?: Required<EdgeLayoutMetadata>;

    /**
     * Whether we're actively tracking a drag.
     */
    protected isTracking = false;

    /**
     * The edge router for computing routes.
     */
    protected edgeRouter: EdgeRouter;

    /**
     * The offset to apply to
     */
    protected offset?: Point;

    /**
     * Feedback emitter for cursor changes.
     */
    protected cursorFeedback;

    /**
     * Constructs a new FeedbackEdgeRouteMovingMouseListener.
     *
     * @param tool The edge edit tool
     */
    constructor(protected tool: EdgeEditTool) {
        super();
        this.edgeRouter = tool.edgeRouter;
        this.cursorFeedback = this.tool.createFeedbackEmitter();
    }

    override mouseDown(target: GModelElement, event: MouseEvent): Action[] {
        const result = super.mouseDown(target, event);

        if (!(target instanceof GEdge)) {
            return result;
        }

        if (event.button != 0) {
            return result;
        }
        const eventTarget = event.target;
        if (!(eventTarget instanceof SVGElement)) {
            return result;
        }
        const segmentIndexStr = eventTarget.dataset["edgeSegmentIndex"];
        if (segmentIndexStr == undefined) {
            return result;
        }

        const segmentIndex = parseInt(segmentIndexStr, 10);

        if (target != undefined && isSelected(target) && !Number.isNaN(segmentIndex)) {
            this.edge = target;
            this.segmentIndex = segmentIndex;
            const routeResult = this.edgeRouter.computeRoute(target);
            this.startMeta = {
                routingPoints: routeResult.route.slice(1, -1),
                sourceAnchor: routeResult.sourceAnchor,
                targetAnchor: routeResult.targetAnchor
            };
            this.isTracking = true;
            const viewport = findParentByFeature(target, isViewport);
            if (viewport != undefined) {
                this.offset = translatePoint(PointUtil.ORIGIN, viewport, target);
            } else {
                this.offset = PointUtil.ORIGIN;
            }
            const sourceOrientation = Orientation.fromSide(this.startMeta.sourceAnchor.side);
            const orientation = segmentIndex % 2 === 0 ? sourceOrientation : Orientation.invert(sourceOrientation);
            this.cursorFeedback
                .add(
                    cursorFeedbackAction(orientation === "horizontal" ? CursorCSS.RESIZE_N : CursorCSS.RESIZE_W),
                    cursorFeedbackAction()
                )
                .submit();
        }

        return result;
    }

    override draggingMouseMove(target: GModelElement, event: MouseEvent): Action[] {
        super.draggingMouseMove(target, event);

        if (!this.isTracking || this.edge == undefined || this.segmentIndex == undefined || this.offset == undefined) {
            return [];
        }

        const mousePosition = PointUtil.add(getAbsolutePosition(target, event), this.offset);
        const newMeta = this.computeRouteWithEditedSegment(this.edge, this.segmentIndex, mousePosition);

        return [
            SetEdgeRoutingFeedbackAction.create({
                elementId: this.edge.id,
                routingPoints: newMeta.routingPoints,
                sourceAnchor: newMeta.sourceAnchor,
                targetAnchor: newMeta.targetAnchor
            })
        ];
    }

    override nonDraggingMouseUp(_element: GModelElement, _event: MouseEvent): Action[] {
        this.resetTracking();
        return [];
    }

    override draggingMouseUp(target: GModelElement, event: MouseEvent): Action[] {
        if (!this.isTracking || this.edge == undefined || this.segmentIndex == undefined || this.offset == undefined) {
            this.resetTracking();
            this.cursorFeedback.add(cursorFeedbackAction(), cursorFeedbackAction()).submit();
            return [];
        }

        const edge = this.edge;
        const mousePosition = PointUtil.add(getAbsolutePosition(target, event), this.offset);
        const newMeta = this.computeRouteWithEditedSegment(edge, this.segmentIndex, mousePosition);

        const routingUpdate = {
            elementId: edge.id,
            routingPoints: newMeta.routingPoints,
            sourceAnchor: newMeta.sourceAnchor,
            targetAnchor: newMeta.targetAnchor
        };
        const operation: UpdateRoutingInformationOperation = {
            kind: UpdateRoutingInformationOperation.KIND,
            isOperation: true,
            updates: [routingUpdate]
        };

        this.resetTracking();
        this.cursorFeedback.add(cursorFeedbackAction(), cursorFeedbackAction()).submit();
        return [operation];
    }

    /**
     * Gets the current edge being edited.
     */
    getEdge(): GEdge | undefined {
        return this.edge;
    }

    /**
     * Gets the current segment index being edited.
     */
    getSegmentIndex(): number | undefined {
        return this.segmentIndex;
    }

    /**
     * Checks if currently tracking a drag.
     */
    isTrackingDrag(): boolean {
        return this.isTracking;
    }

    /**
     * Resets the tracking state.
     */
    resetTracking(): void {
        this.edge = undefined;
        this.segmentIndex = undefined;
        this.startMeta = undefined;
        this.isTracking = false;
        this.offset = undefined;
    }

    override dispose(): void {
        this.resetTracking();
        this.cursorFeedback.dispose();
        super.dispose();
    }

    /**
     * Computes a route with updated metadata for a specific segment edit.
     * This is used when dragging a segment of the edge to a new position.
     *
     * The route on the class does NOT include the point the anchor represents,
     * meaning the first point is the end of the first segment, and the last is
     * the start of the segment connecting to the target box.
     *
     * @param edge The edge being edited
     * @param segmentIndex The index of the segment being edited (0 = first segment from start)
     * @param newPoint The new point position for the segment corner
     * @returns The updated metadata that should be applied
     */
    protected computeRouteWithEditedSegment(edge: GEdge, segmentIndex: number, newPoint: Point): EdgeLayoutMetadata {
        const sourceBounds = (edge.index.getById(edge.sourceId) as BoundsAware | undefined)?.bounds;
        const targetBounds = (edge.index.getById(edge.targetId) as BoundsAware | undefined)?.bounds;

        if (sourceBounds == undefined || targetBounds == undefined || !this.startMeta) {
            return this.startMeta ?? edge.meta;
        }

        const snappedPoint = this.edgeRouter.snapper.snap(newPoint, edge);

        const { cases, routingPoints: points } = this.extractCasesAndRoutingPoints(
            sourceBounds,
            targetBounds,
            segmentIndex
        );
        let { sourceAnchor, targetAnchor } = this.startMeta;
        let routingPoints = points;

        if (cases.includes("first")) {
            const result = this.handleFirstSegmentEdit(edge, routingPoints, sourceAnchor, snappedPoint, sourceBounds);
            routingPoints = result.routingPoints;
            sourceAnchor = result.sourceAnchor;
        }
        if (cases.includes("last")) {
            const result = this.handleLastSegmentEdit(edge, routingPoints, targetAnchor, snappedPoint, targetBounds);
            routingPoints = result.routingPoints;
            targetAnchor = result.targetAnchor;
        }
        if (cases.includes("second")) {
            const result = this.handleSecondSegmentEdit(edge, routingPoints, sourceAnchor, snappedPoint, sourceBounds);
            routingPoints = result.routingPoints;
            sourceAnchor = result.sourceAnchor;
        }
        if (cases.includes("secondToLast")) {
            const result = this.handleSecondToLastSegmentEdit(
                edge,
                routingPoints,
                targetAnchor,
                snappedPoint,
                targetBounds
            );
            routingPoints = result.routingPoints;
            targetAnchor = result.targetAnchor;
        }
        if (cases.includes("middle")) {
            const sourceOrientation = Orientation.fromSide(sourceAnchor.side);
            routingPoints = this.handleMiddleSegmentEdit(
                segmentIndex,
                routingPoints,
                snappedPoint,
                segmentIndex % 2 === 0 ? sourceOrientation : Orientation.invert(sourceOrientation)
            );
        }

        const newSourceAnchorPoint = this.edgeRouter.anchorToPoint(sourceAnchor, sourceBounds);
        const newTargetAnchorPoint = this.edgeRouter.anchorToPoint(targetAnchor, targetBounds);

        const simplifiedPoints = this.edgeRouter.simplifyRoutingPoints([
            newSourceAnchorPoint,
            ...routingPoints,
            newTargetAnchorPoint
        ]);

        return {
            routingPoints: this.edgeRouter.routingPointsFromRoute(simplifiedPoints),
            sourceAnchor,
            targetAnchor
        };
    }

    /**
     * Extracts the cases and routing points based on the current segment index.
     * Handles special cases like no routing points, or only one or two routing points.
     * Introduces virtual routing points which are automatically removed by cleanup later, but allow for easier handling.
     *
     * @param sourceBounds the bounds of the source element
     * @param targetBounds the bounds of the target element
     * @param segmentIndex the index of the segment being edited
     * @returns The cases and routing points
     */
    private extractCasesAndRoutingPoints(
        sourceBounds: BoundsType,
        targetBounds: BoundsType,
        segmentIndex: number
    ): {
        cases: ("middle" | "first" | "last" | "second" | "secondToLast")[];
        routingPoints: Point[];
    } {
        let routingPoints = [...this.startMeta!.routingPoints];
        const sourceAnchorPoint = this.edgeRouter.anchorToPoint(this.startMeta!.sourceAnchor, sourceBounds);
        const targetAnchorPoint = this.edgeRouter.anchorToPoint(this.startMeta!.targetAnchor, targetBounds);
        const cases: ("first" | "last" | "second" | "secondToLast" | "middle")[] = [];
        if (routingPoints.length === 0) {
            cases.push("first", "last");
            routingPoints = [PointUtil.linear(sourceAnchorPoint, targetAnchorPoint, 0.5)];
        } else if (routingPoints.length === 1) {
            if (segmentIndex === 0) {
                cases.push("first", "secondToLast");
                routingPoints = [routingPoints[0], sourceAnchorPoint, routingPoints[0]];
            } else {
                cases.push("second", "last");
                routingPoints = [routingPoints[0], targetAnchorPoint, routingPoints[0]];
            }
        } else if (routingPoints.length === 2) {
            if (segmentIndex === 0) {
                cases.push("first");
            } else if (segmentIndex === 1) {
                cases.push("second", "secondToLast");
                routingPoints = [routingPoints[0], routingPoints[1], routingPoints[0], routingPoints[1]];
            } else {
                cases.push("last");
            }
        } else {
            if (segmentIndex === 0) {
                cases.push("first");
            } else if (segmentIndex === routingPoints.length) {
                cases.push("last");
            } else if (segmentIndex === 1) {
                cases.push("second");
            } else if (segmentIndex === routingPoints.length - 1) {
                cases.push("secondToLast");
            } else {
                cases.push("middle");
            }
        }
        return { cases, routingPoints };
    }

    /**
     * Handles editing the first segment of the edge.
     *
     * @param edge The edge being edited
     * @param routingPoints The current routing points
     * @param sourceAnchor The current start anchor
     * @param snappedPoint The snapped point for the segment edit
     * @param sourceBounds The bounds of the source element
     * @returns The updated routing points and start anchor
     */
    protected handleFirstSegmentEdit(
        edge: GEdge,
        routingPoints: Point[],
        sourceAnchor: EdgeAnchor,
        snappedPoint: Point,
        sourceBounds: BoundsType
    ): { routingPoints: Point[]; sourceAnchor: EdgeAnchor } {
        const firstPoint = routingPoints[0];
        if (!firstPoint) {
            return { routingPoints, sourceAnchor };
        }

        const orientation = Orientation.fromSide(sourceAnchor.side);
        const updatedFirstPoint = this.applySnappedCoordinate(firstPoint, snappedPoint, orientation);
        const projection = this.edgeRouter.projectAnchor(edge, updatedFirstPoint, sourceBounds, orientation);
        const updatedPoints = [...routingPoints];
        updatedPoints[0] = updatedFirstPoint;

        if (projection.anchor.side === sourceAnchor.side) {
            return { routingPoints: updatedPoints, sourceAnchor: projection.anchor };
        } else {
            const anchorPoint = this.edgeRouter.anchorToPoint(projection.anchor, sourceBounds);
            const cornerPoint = this.createCornerPoint(anchorPoint, updatedFirstPoint, projection.anchor.side);
            return { routingPoints: [cornerPoint, ...updatedPoints], sourceAnchor: projection.anchor };
        }
    }

    /**
     * Handles editing the last segment of the edge.
     *
     * @param edge The edge being edited
     * @param routingPoints The current routing points
     * @param targetAnchor The current target anchor
     * @param snappedPoint The snapped point for the segment edit
     * @param targetBounds The bounds of the target element
     * @returns The updated routing points and target anchor
     */
    protected handleLastSegmentEdit(
        edge: GEdge,
        routingPoints: Point[],
        targetAnchor: EdgeAnchor,
        snappedPoint: Point,
        targetBounds: BoundsType
    ): { routingPoints: Point[]; targetAnchor: EdgeAnchor } {
        const lastIndex = routingPoints.length - 1;
        const lastPoint = routingPoints[lastIndex];

        const orientation = Orientation.fromSide(targetAnchor.side);
        const updatedLastPoint = this.applySnappedCoordinate(lastPoint, snappedPoint, orientation);
        const projection = this.edgeRouter.projectAnchor(edge, updatedLastPoint, targetBounds, orientation);
        const updatedPoints = [...routingPoints];
        updatedPoints[lastIndex] = updatedLastPoint;

        if (projection.isDirect) {
            return { routingPoints: updatedPoints, targetAnchor: projection.anchor };
        } else {
            const anchorPoint = this.edgeRouter.anchorToPoint(projection.anchor, targetBounds);
            const cornerPoint = this.createCornerPoint(anchorPoint, updatedLastPoint, projection.anchor.side);
            return { routingPoints: [...updatedPoints, cornerPoint], targetAnchor: projection.anchor };
        }
    }

    /**
     * Handles editing the second segment of the edge.
     *
     * @param edge The edge being edited
     * @param routingPoints The current routing points
     * @param sourceAnchor The current start anchor
     * @param snappedPoint The snapped point for the segment edit
     * @param sourceBounds The bounds of the source element
     * @returns The updated routing points and start anchor
     */
    protected handleSecondSegmentEdit(
        edge: GEdge,
        routingPoints: Point[],
        sourceAnchor: EdgeAnchor,
        snappedPoint: Point,
        sourceBounds: BoundsType
    ): { routingPoints: Point[]; sourceAnchor: EdgeAnchor } {
        const firstPoint = routingPoints[0];
        const secondPoint = routingPoints[1];

        const orientation = Orientation.invert(Orientation.fromSide(sourceAnchor.side));
        const updatedSecondPoint = this.applySnappedCoordinate(secondPoint, snappedPoint, orientation);
        const projection = this.edgeRouter.projectAnchor(edge, updatedSecondPoint, sourceBounds);

        if (
            projection.isDirect &&
            Orientation.fromSide(projection.anchor.side) !== Orientation.fromSide(sourceAnchor.side)
        ) {
            return {
                routingPoints: [updatedSecondPoint, ...routingPoints.slice(2)],
                sourceAnchor: projection.anchor
            };
        } else {
            const updatedFirstPoint = this.applySnappedCoordinate(firstPoint, snappedPoint, orientation);
            const firstProjection = this.edgeRouter.projectAnchor(edge, updatedFirstPoint, sourceBounds, orientation);
            const updatedPoints = [...routingPoints];
            updatedPoints[0] = updatedFirstPoint;
            updatedPoints[1] = updatedSecondPoint;
            return { routingPoints: updatedPoints, sourceAnchor: firstProjection.anchor };
        }
    }

    /**
     * Handles editing the second-to-last segment of the edge.
     *
     * @param edge The edge being edited
     * @param routingPoints The current routing points
     * @param targetAnchor The current target anchor
     * @param snappedPoint The snapped point for the segment edit
     * @param targetBounds The bounds of the target element
     * @returns The updated routing points and target anchor
     */
    protected handleSecondToLastSegmentEdit(
        edge: GEdge,
        routingPoints: Point[],
        targetAnchor: EdgeAnchor,
        snappedPoint: Point,
        targetBounds: BoundsType
    ): { routingPoints: Point[]; targetAnchor: EdgeAnchor } {
        const penultimateIndex = routingPoints.length - 2;
        const lastIndex = routingPoints.length - 1;
        const penultimatePoint = routingPoints[penultimateIndex];
        const lastPoint = routingPoints[lastIndex];

        const orientation = Orientation.invert(Orientation.fromSide(targetAnchor.side));
        const updatedPenultimate = this.applySnappedCoordinate(penultimatePoint, snappedPoint, orientation);
        const projection = this.edgeRouter.projectAnchor(edge, updatedPenultimate, targetBounds);

        if (
            projection.isDirect &&
            Orientation.fromSide(projection.anchor.side) !== Orientation.fromSide(targetAnchor.side)
        ) {
            return {
                routingPoints: [...routingPoints.slice(0, penultimateIndex), updatedPenultimate],
                targetAnchor: projection.anchor
            };
        } else {
            const updatedLast = this.applySnappedCoordinate(lastPoint, snappedPoint, orientation);
            const lastProjection = this.edgeRouter.projectAnchor(edge, updatedLast, targetBounds, orientation);
            const updatedPoints = [...routingPoints];
            updatedPoints[penultimateIndex] = updatedPenultimate;
            updatedPoints[lastIndex] = updatedLast;
            return { routingPoints: updatedPoints, targetAnchor: lastProjection.anchor };
        }
    }

    /**
     * Handles editing a middle segment of the edge.
     *
     * @param segmentIndex The index of the segment being edited
     * @param routingPoints The current routing points
     * @param snappedPoint The snapped point for the segment edit
     * @param orientation The orientation of the segment
     * @returns The updated routing points
     */
    protected handleMiddleSegmentEdit(
        segmentIndex: number,
        routingPoints: Point[],
        snappedPoint: Point,
        orientation: Orientation
    ): Point[] {
        const startIndex = segmentIndex - 1;
        const endIndex = segmentIndex;
        if (startIndex < 0 || endIndex >= routingPoints.length) {
            return routingPoints;
        }

        const segmentStart = routingPoints[startIndex];
        const segmentEnd = routingPoints[endIndex];
        if (!segmentStart || !segmentEnd) {
            return routingPoints;
        }

        const updatedStart = this.applySnappedCoordinate(segmentStart, snappedPoint, orientation);
        const updatedEnd = this.applySnappedCoordinate(segmentEnd, snappedPoint, orientation);
        const updatedPoints = [...routingPoints];
        updatedPoints[startIndex] = updatedStart;
        updatedPoints[endIndex] = updatedEnd;
        return updatedPoints;
    }

    /**
     * Applies the snapped coordinate to a point based on orientation.
     *
     * @param point The original point
     * @param snappedPoint The snapped point
     * @param orientation The orientation ("horizontal" or "vertical")
     * @returns The adjusted point
     */
    protected applySnappedCoordinate(point: Point, snappedPoint: Point, orientation: "horizontal" | "vertical"): Point {
        if (orientation === "vertical") {
            return { x: snappedPoint.x, y: point.y };
        }
        return { x: point.x, y: snappedPoint.y };
    }

    /**
     * Creates a corner point when transitioning between anchor and routing point.
     *
     * @param anchorPoint The anchor point
     * @param neighbor The neighboring point
     * @param side The side of the anchor
     * @returns The corner point
     */
    protected createCornerPoint(anchorPoint: Point, neighbor: Point, side: AnchorSide): Point {
        if (side === "left" || side === "right") {
            return { x: neighbor.x, y: anchorPoint.y };
        }
        return { x: anchorPoint.x, y: neighbor.y };
    }
}
