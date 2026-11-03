import type { RenderingContext, IView, Point, Bounds, GModelElement, BoundsAware } from "@eclipse-glsp/sprotty";
import { sharedImport } from "../sharedImport.js";
import type { GEdge, EdgeReconnectData } from "../model/edge.js";
import type { VNode } from "snabbdom";
import { EdgeRouter, type RouteComputationResult } from "../features/edge-routing/edgeRouter.js";
import type { AnchorSide, EdgeLabelGeometrySpec } from "@mdeo/protocol-common";
import { endLabelKey, findMiddleSegment, placeEdgeLabels, EDGE_LABEL_SPACING } from "@mdeo/protocol-common";
import { findViewportZoom } from "../base/findViewportZoom.js";
import type { ContextActionRailOrientation } from "../features/context-actions/contextActions.js";
import { isIssueMarker, ISSUE_MARKER_SIZE } from "../features/decoration/issueMarker.js";
import { ToolStateManager } from "../features/tool-state/toolStateManager.js";

const { injectable, inject } = sharedImport("inversify");
const { svg, Point: PointUtil } = sharedImport("@eclipse-glsp/sprotty");
const { isSelected } = sharedImport("@eclipse-glsp/client");

/**
 * Data returned by marker render methods.
 */
export interface EdgeMarkerData {
    /**
     * The VNode representing the marker
     */
    marker: VNode;
    /**
     * How much the path stroke start/end point should be moved away from the original point
     */
    strokeOffset: number;
    /**
     * How much elements (like labels) should be offset from the original point
     */
    elementOffset: number;
}

/**
 * Position of an edge attachment.
 *
 * SOURCE_LEFT, SOURCE_RIGHT, TARGET_LEFT, TARGET_RIGHT, MIDDLE_LEFT, MIDDLE_RIGHT are for text/label
 * attachments (properties, multiplicity, etc.) and use a dedicated stacking algorithm.
 * START, END, MIDDLE are used exclusively by context action rails with a separate placement algorithm.
 */
export enum EdgeAttachmentPosition {
    /**
     * Label attachment at the source end, on the left/above side of the edge line
     */
    SOURCE_LEFT = "source-left",
    /**
     * Label attachment at the source end, on the right/below side of the edge line
     */
    SOURCE_RIGHT = "source-right",
    /**
     * Label attachment at the target end, on the left/above side of the edge line
     */
    TARGET_LEFT = "target-left",
    /**
     * Label attachment at the target end, on the right/below side of the edge line
     */
    TARGET_RIGHT = "target-right",
    /**
     * Label attachment at the edge midpoint, on the left/above side of the edge line
     */
    MIDDLE_LEFT = "middle-left",
    /**
     * Label attachment at the edge midpoint, on the right/below side of the edge line
     */
    MIDDLE_RIGHT = "middle-right",
    /**
     * Context action rail at the start/source endpoint of the edge
     */
    START = "start",
    /**
     * Context action rail at the end/target endpoint of the edge
     */
    END = "end",
    /**
     * Context action rail at the midpoint of the edge
     */
    MIDDLE = "middle"
}

/**
 * Interface for edge attachment data.
 * Attachments are additional visual elements positioned relative to the edge.
 */
export interface EdgeAttachment {
    /**
     * The VNode to render for this attachment
     */
    vnode: VNode | undefined;
    /**
     * Optional renderer callback for attachments that need the final absolute position.
     */
    renderAt?: (position: Point, orientation: ContextActionRailOrientation) => VNode | undefined;
    /**
     * Layout orientation used when renderAt is provided.
     */
    orientation?: ContextActionRailOrientation;
    /**
     * The bounds of the attachment element
     */
    bounds: Bounds;
    /**
     * The target position along the edge
     */
    position: EdgeAttachmentPosition;
    /**
     * When true, this attachment is placed on the opposite side of the midpoint
     * from where visual MIDDLE attachments go. Used by context action rails at MIDDLE.
     */
    oppositeMiddleSide?: boolean;
    /**
     * The model element rendered by this attachment (label attachments), used for the label text
     * (detection of identical end labels) and for the edit mode (labels are not rotated while editing).
     */
    element?: GModelElement;
}

/**
 * A label element of an edge together with its attachment position.
 */
export interface EdgeLabelElement {
    /**
     * The label element (typically a GNode child of the edge wrapping a text label).
     */
    element: GModelElement & BoundsAware;
    /**
     * The label position (one of the *_LEFT / *_RIGHT label positions).
     */
    position: EdgeAttachmentPosition;
}

/**
 * Information about one end of an edge whose labels are shared with other edges ending at the same point.
 */
interface SharedEndInfo {
    /**
     * Whether this edge renders the shared labels / marker / trunk (the representative of the group).
     */
    representative: boolean;
    /**
     * Length of the end segment that is drawn by the representative as well (0 for representatives).
     */
    sharedLength: number;
}

/**
 * Cache of the edges attached to each node, per model root.
 */
const edgesByNodeCache = new WeakMap<object, Map<string, GEdge[]>>();

/**
 * Abstract base view for rendering edge elements.
 * Provides a foundation for custom edge visualizations with support for markers.
 */
@injectable()
export abstract class GEdgeView implements IView {
    @inject(EdgeRouter) protected edgeRouter!: EdgeRouter;

    /**
     * Tool state manager used to suppress the reconnect handles while a creation
     * tool is active, keeping the canvas visually clean.
     */
    @inject(ToolStateManager) protected toolStateManager!: ToolStateManager;

    /**
     * Distance from the edge line to the first attachment (in pixels)
     */
    protected attachmentDistanceToLine = 2;

    /**
     * Size of the reconnect handle (in pixels at zoom 1.0)
     */
    static readonly RECONNECT_HANDLE_SIZE = 20;

    /**
     * Size of the inner reconnect handle (in pixels at zoom 1.0)
     */
    static readonly RECONNECT_HANDLE_INNER_SIZE = 10;

    /**
     * Renders the edge element with optional markers, visible path, invisible selection path, and additional content.
     *
     * @param model The edge model to render
     * @param context The rendering context
     * @returns The rendered VNode or undefined if rendering is not possible
     */
    render(model: Readonly<GEdge>, context: RenderingContext): VNode | undefined {
        const routeResult = this.edgeRouter.computeRoute(model);
        const route = routeResult.route;

        const sourceMarkerData = this.renderSourceMarker(model, context);
        const targetMarkerData = this.renderTargetMarker(model, context);

        const attachments = this.getEdgeAttachments(model, context);
        const sourceElementOffset = sourceMarkerData?.elementOffset ?? 0;
        const targetElementOffset = targetMarkerData?.elementOffset ?? 0;

        const sharedSource = this.getSharedEndInfo(model, context, route, attachments, "source");
        const sharedTarget = this.getSharedEndInfo(model, context, route, attachments, "target");
        const selected = isSelected(model);
        const skipSource = sharedSource != undefined && !sharedSource.representative;
        const skipTarget = sharedTarget != undefined && !sharedTarget.representative;

        const startStrokeOffset = sourceMarkerData?.strokeOffset ?? 0;
        const endStrokeOffset = targetMarkerData?.strokeOffset ?? 0;

        const adjustedRoute = this.adjustRouteForMarkers(route, startStrokeOffset, endStrokeOffset);
        const visibleRoute = selected
            ? adjustedRoute
            : this.trimSharedSegments(
                  adjustedRoute,
                  skipSource ? sharedSource.sharedLength - startStrokeOffset : 0,
                  skipTarget ? sharedTarget.sharedLength - endStrokeOffset : 0
              );

        const children: VNode[] = [];

        if (sourceMarkerData && route.length >= 2 && (!skipSource || selected)) {
            const sourceMarker = this.transformMarker(sourceMarkerData.marker, route[0], route[1], "source");
            children.push(sourceMarker);
        }

        if (targetMarkerData && route.length >= 2 && (!skipTarget || selected)) {
            const targetMarker = this.transformMarker(
                targetMarkerData.marker,
                route[route.length - 1],
                route[route.length - 2],
                "target"
            );
            children.push(targetMarker);
        }

        if (visibleRoute.length >= 2) {
            children.push(this.renderVisiblePath(visibleRoute, model, context));
        }

        children.push(...this.renderInvisiblePath(adjustedRoute, model));

        children.push(...this.renderAdditional(model, context, routeResult));

        const visibleAttachments = attachments.filter((attachment) => {
            if (skipSource && GEdgeView.isSourceLabelPosition(attachment.position)) {
                return false;
            }
            if (skipTarget && GEdgeView.isTargetLabelPosition(attachment.position)) {
                return false;
            }
            return true;
        });
        children.push(
            ...this.renderAttachments(
                visibleAttachments,
                routeResult,
                sourceElementOffset,
                targetElementOffset,
                context
            )
        );

        if (selected && route.length >= 2 && !this.toolStateManager.isCreationMode()) {
            children.push(...this.renderReconnectHandles(model, route));
        }

        if (model.edgeCreateData && route.length >= 2) {
            children.push(...this.renderCreateEdgeEndpoints(model, route));
        }

        children.push(...this.renderIssueMarkers(model, route, context));

        const rootClasses: Record<string, boolean> = {};
        if (!selected) {
            rootClasses["cursor-pointer"] = true;
        }
        if (model.reconnectData != undefined || model.edgeCreateData != undefined) {
            rootClasses["pointer-events-none"] = true;
            rootClasses["[&_*]:pointer-events-none!"] = true;
        }

        return svg("g", { class: rootClasses }, ...children);
    }

    /**
     * Determines whether the labels at one end of this edge are shared with other edges: other edges that end at
     * exactly the same point of the same node, with an identical set of end label texts and the same marker.
     * Of such a group, only the edge with the lowest id (the representative) renders the labels, the marker and the
     * shared end segment (the joined trunk), so they are drawn only once (no antialiasing artifacts).
     *
     * @param model the edge
     * @param context the rendering context
     * @param route the route of the edge
     * @param attachments the attachments of the edge
     * @param end the end
     * @returns the shared end info, or undefined if the end is not shared
     */
    protected getSharedEndInfo(
        model: Readonly<GEdge>,
        context: RenderingContext,
        route: Point[],
        attachments: EdgeAttachment[],
        end: "source" | "target"
    ): SharedEndInfo | undefined {
        if (context.targetKind === "hidden" || route.length < 2 || model.reconnectData || model.edgeCreateData) {
            return undefined;
        }
        const key = endLabelKey(this.toLabelSpecs(attachments), end);
        if (key == undefined) {
            return undefined;
        }
        const nodeId = end === "source" ? model.sourceId : model.targetId;
        const endpoint = end === "source" ? route[0] : route[route.length - 1];
        const ownMarker = this.markerLengthAt(model, context, end);
        const ownSegment = GEdgeView.endSegmentOf(route, end);

        let representative = true;
        let shared = false;
        let sharedLength = 0;
        for (const other of this.getEdgesAtNode(model, nodeId)) {
            if (other.id === model.id || other.reconnectData || other.edgeCreateData) {
                continue;
            }
            for (const otherEnd of ["source", "target"] as const) {
                if ((otherEnd === "source" ? other.sourceId : other.targetId) !== nodeId) {
                    continue;
                }
                const otherKey = endLabelKey(
                    this.toLabelSpecs(this.getLabelAttachmentsWithoutRendering(other)),
                    otherEnd
                );
                if (otherKey !== key || this.markerLengthAt(other, context, otherEnd) !== ownMarker) {
                    continue;
                }
                const otherRoute = this.edgeRouter.computeRoute(other).route;
                if (otherRoute.length < 2) {
                    continue;
                }
                const otherPoint = otherEnd === "source" ? otherRoute[0] : otherRoute[otherRoute.length - 1];
                if (Math.abs(otherPoint.x - endpoint.x) > 0.5 || Math.abs(otherPoint.y - endpoint.y) > 0.5) {
                    continue;
                }
                shared = true;
                if (other.id < model.id) {
                    representative = false;
                    const otherSegment = GEdgeView.endSegmentOf(otherRoute, otherEnd);
                    if (
                        ownSegment != undefined &&
                        otherSegment != undefined &&
                        ownSegment.direction.x === otherSegment.direction.x &&
                        ownSegment.direction.y === otherSegment.direction.y
                    ) {
                        sharedLength = Math.max(sharedLength, Math.min(ownSegment.length, otherSegment.length));
                    }
                }
            }
        }
        if (!shared) {
            return undefined;
        }
        return { representative, sharedLength: representative ? 0 : sharedLength };
    }

    /**
     * Returns the label attachments of another edge without rendering them (vnodes are undefined).
     * Only labels that this view recognizes are returned (other edge types yield no labels).
     *
     * @param model the edge
     * @returns the attachments
     */
    private getLabelAttachmentsWithoutRendering(model: Readonly<GEdge>): EdgeAttachment[] {
        return this.getLabelElements(model).map((label) => ({
            vnode: undefined,
            bounds: label.element.bounds,
            position: label.position,
            element: label.element
        }));
    }

    /**
     * Returns the length of the marker at an end as used for label placement (the distance from the node border to
     * the end of the marker, 0 if there is no marker).
     *
     * @param model the edge
     * @param context the rendering context
     * @param end the end
     * @returns the marker length
     */
    protected markerLengthAt(model: Readonly<GEdge>, context: RenderingContext, end: "source" | "target"): number {
        const marker =
            end === "source" ? this.renderSourceMarker(model, context) : this.renderTargetMarker(model, context);
        if (marker == undefined || marker.elementOffset <= 0) {
            return 0;
        }
        return marker.elementOffset + EDGE_LABEL_SPACING.distanceFromElement;
    }

    /**
     * Returns all edges attached to a node (cached per model root).
     *
     * @param model an edge of the model
     * @param nodeId the node
     * @returns the edges whose source or target is the node
     */
    protected getEdgesAtNode(model: Readonly<GEdge>, nodeId: string): GEdge[] {
        const root = model.root;
        let byNode = edgesByNodeCache.get(root);
        if (byNode == undefined) {
            byNode = new Map();
            for (const element of model.index.all()) {
                const candidate = element as unknown as GEdge;
                if (
                    typeof candidate.sourceId === "string" &&
                    typeof candidate.targetId === "string" &&
                    "meta" in candidate
                ) {
                    for (const id of new Set([candidate.sourceId, candidate.targetId])) {
                        const list = byNode.get(id) ?? [];
                        list.push(candidate);
                        byNode.set(id, list);
                    }
                }
            }
            edgesByNodeCache.set(root, byNode);
        }
        return (byNode.get(nodeId) ?? []).filter((edge) => model.index.getById(edge.id) === edge);
    }

    /**
     * Computes the first non-degenerate segment at an end of a route.
     *
     * @param route the route
     * @param end the end
     * @returns the axis direction away from the node and the segment length, or undefined
     */
    private static endSegmentOf(
        route: Point[],
        end: "source" | "target"
    ): { direction: Point; length: number } | undefined {
        const points = end === "source" ? route : [...route].reverse();
        for (let i = 1; i < points.length; i++) {
            const dx = points[i].x - points[0].x;
            const dy = points[i].y - points[0].y;
            if (Math.abs(dx) > 1e-6 || Math.abs(dy) > 1e-6) {
                if (Math.abs(dx) > 1e-6 && Math.abs(dy) > 1e-6) {
                    return undefined;
                }
                return {
                    direction: { x: Math.sign(dx), y: Math.sign(dy) },
                    length: Math.abs(dx) + Math.abs(dy)
                };
            }
        }
        return undefined;
    }

    /**
     * Removes the given lengths from the start / end of a route (used to not draw shared trunk segments twice).
     *
     * @param route the route
     * @param startLength the length to remove at the start
     * @param endLength the length to remove at the end
     * @returns the trimmed route (may have fewer than two points if nothing remains)
     */
    private trimSharedSegments(route: Point[], startLength: number, endLength: number): Point[] {
        let result = route;
        if (startLength > 0.5) {
            result = GEdgeView.trimStart(result, startLength);
        }
        if (endLength > 0.5) {
            result = GEdgeView.trimStart([...result].reverse(), endLength).reverse();
        }
        return result;
    }

    /**
     * Removes the given length from the start of a route.
     *
     * @param route the route
     * @param length the length to remove
     * @returns the trimmed route
     */
    private static trimStart(route: Point[], length: number): Point[] {
        let remaining = length;
        for (let i = 0; i < route.length - 1; i++) {
            const segmentLength = Math.hypot(route[i + 1].x - route[i].x, route[i + 1].y - route[i].y);
            if (segmentLength >= remaining - 1e-6) {
                const t = segmentLength > 0 ? remaining / segmentLength : 0;
                const start = PointUtil.linear(route[i], route[i + 1], t);
                const rest = route.slice(i + 1);
                return segmentLength - remaining > 1e-6 ? [start, ...rest] : rest;
            }
            remaining -= segmentLength;
        }
        return [];
    }

    /**
     * Renders the source marker for the edge.
     * By default, returns undefined which indicates no marker.
     *
     * Markers should be rendered at origin (0,0) pointing right (towards positive x-axis).
     * The marker will be automatically transformed and rotated to align with the edge direction.
     *
     * @param _model The edge model
     * @param _context The rendering context
     * @returns Marker data or undefined if no marker should be rendered
     */
    protected renderSourceMarker(_model: Readonly<GEdge>, _context: RenderingContext): EdgeMarkerData | undefined {
        return undefined;
    }

    /**
     * Renders the target marker for the edge.
     * By default, returns undefined which indicates no marker.
     *
     * Markers should be rendered at origin (0,0) pointing right (towards positive x-axis).
     * The marker will be automatically transformed and rotated to align with the edge direction.
     *
     * @param _model The edge model
     * @param _context The rendering context
     * @returns Marker data or undefined if no marker should be rendered
     */
    protected renderTargetMarker(_model: Readonly<GEdge>, _context: RenderingContext): EdgeMarkerData | undefined {
        return undefined;
    }

    /**
     * Renders issue marker badges for all GIssueMarker children of the edge.
     *
     * The badge is placed at the geometric midpoint of the middle route segment so
     * it stays visually associated with the edge regardless of its length or orientation.
     * The badge size remains constant in physical CSS pixels regardless of zoom level.
     *
     * @param model   The edge model whose children are searched for GIssueMarker elements.
     * @param route   The computed route points (must have at least two points).
     * @param context The current rendering context used to render the marker view.
     * @returns An array of SVG VNodes for the issue marker badges (empty when there are none).
     */
    private renderIssueMarkers(model: Readonly<GEdge>, route: Point[], context: RenderingContext): VNode[] {
        if (route.length < 2) {
            return [];
        }

        const markers = model.children.filter(isIssueMarker);
        if (markers.length === 0) {
            return [];
        }

        const zoom = findViewportZoom(model);
        const iconSvgSize = ISSUE_MARKER_SIZE / zoom;

        const markerVNode = context.renderElement(markers[0]);
        if (markerVNode == undefined) {
            return [];
        }

        const midIndex = findMiddleSegment(route);
        const midPoint = PointUtil.linear(route[midIndex], route[midIndex + 1], 0.5);

        const x = midPoint.x - iconSvgSize / 2;
        const y = midPoint.y - iconSvgSize / 2;

        return [svg("g", { attrs: { transform: `translate(${x}, ${y})` } }, markerVNode)];
    }

    /**
     * Renders additional content for the edge (e.g., labels, decorations).
     * Override this method to add custom elements to the edge.
     *
     * @param _model The edge model
     * @param _context The rendering context
     * @param _routeData The route data including offsets
     * @returns An array of VNodes to render
     */
    protected renderAdditional(
        _model: Readonly<GEdge>,
        _context: RenderingContext,
        _routeResult: RouteComputationResult
    ): VNode[] {
        return [];
    }

    /**
     * Gets edge attachments to be rendered along the edge.
     * The default implementation renders the label elements returned by {@link getLabelElements}.
     * Override this method to provide additional custom attachments.
     *
     * @param model The edge model
     * @param context The rendering context
     * @returns An array of edge attachments
     */
    protected getEdgeAttachments(model: Readonly<GEdge>, context: RenderingContext): EdgeAttachment[] {
        return this.getLabelElements(model).map((label) => ({
            vnode: context.renderElement(label.element),
            bounds: label.element.bounds,
            position: label.position,
            element: label.element
        }));
    }

    /**
     * Gets the label elements of the edge with their positions (source / target / middle, left / right).
     * Must not render anything: it is also used to compare the end labels of neighbouring edges.
     *
     * @param _model The edge model
     * @returns The label elements
     */
    protected getLabelElements(_model: Readonly<GEdge>): EdgeLabelElement[] {
        return [];
    }

    /**
     * Converts label attachments into the shared label geometry specs.
     *
     * @param attachments the attachments (non-label attachments are ignored)
     * @returns the specs, in the order of the attachments
     */
    protected toLabelSpecs(attachments: EdgeAttachment[]): (EdgeLabelGeometrySpec & { attachment: EdgeAttachment })[] {
        const specs: (EdgeLabelGeometrySpec & { attachment: EdgeAttachment })[] = [];
        attachments.forEach((attachment, index) => {
            if (!GEdgeView.isLabelPosition(attachment.position)) {
                return;
            }
            const end = GEdgeView.isSourceLabelPosition(attachment.position)
                ? "source"
                : GEdgeView.isTargetLabelPosition(attachment.position)
                  ? "target"
                  : "middle";
            specs.push({
                id: attachment.element?.id ?? `#${index}`,
                end,
                side: GEdgeView.determineAttachmentSide(attachment.position) ? "left" : "right",
                width: attachment.bounds.width,
                height: attachment.bounds.height,
                text: attachment.element != undefined ? collectLabelText(attachment.element) : undefined,
                attachment
            });
        });
        return specs;
    }

    /**
     * Renders edge attachments at their computed positions.
     * Label attachments are placed with the shared label geometry ({@link placeEdgeLabels}): they run along their
     * segment and are rotated by -90 degrees on vertical segments (except while being edited and in the hidden
     * bounds computation, where the unrotated size is measured).
     *
     * @param attachments The attachments to render
     * @param routeResult The route data
     * @param sourceElementOffset Offset from the source element due to markers
     * @param targetElementOffset Offset from the target element due to markers
     * @param context The rendering context
     * @returns An array of VNodes for the attachments
     */
    private renderAttachments(
        attachments: EdgeAttachment[],
        routeResult: RouteComputationResult,
        sourceElementOffset: number,
        targetElementOffset: number,
        context: RenderingContext
    ): VNode[] {
        const vnodes: VNode[] = [];
        const route = routeResult.route;
        if (route.length < 2) {
            return vnodes;
        }

        const labelSpecs = this.toLabelSpecs(attachments);
        if (labelSpecs.length > 0) {
            const markerLength = (offset: number) => (offset > 0 ? offset + EDGE_LABEL_SPACING.distanceFromElement : 0);
            const placed = placeEdgeLabels(route, labelSpecs, {
                source: { length: markerLength(sourceElementOffset) },
                target: { length: markerLength(targetElementOffset) }
            });
            const hidden = context.targetKind === "hidden";
            for (const placement of placed) {
                const attachment = (placement.label as EdgeLabelGeometrySpec & { attachment: EdgeAttachment })
                    .attachment;
                const unrotated =
                    hidden ||
                    placement.rotation === 0 ||
                    (attachment.element != undefined && isInEditMode(attachment.element));
                const transform = unrotated
                    ? `translate(${placement.center.x - attachment.bounds.width / 2}, ${
                          placement.center.y - attachment.bounds.height / 2
                      })`
                    : placement.transform;
                vnodes.push(svg("g", { attrs: { transform } }, attachment.vnode));
            }
        }

        const groupedAttachments = new Map<EdgeAttachmentPosition, EdgeAttachment[]>();
        for (const attachment of attachments) {
            if (GEdgeView.isLabelPosition(attachment.position)) {
                continue;
            }
            const group = groupedAttachments.get(attachment.position) ?? [];
            group.push(attachment);
            groupedAttachments.set(attachment.position, group);
        }

        for (const [position, group] of groupedAttachments) {
            const anchorInfo = this.getAnchorInfo(position, routeResult);
            if (anchorInfo) {
                // Context rail positions (START, END, MIDDLE): no distanceFromElement for START/END
                vnodes.push(...this.stackAttachments(group, anchorInfo.anchorPoint, position, anchorInfo));
            }
        }

        return vnodes;
    }

    /**
     * Gets the anchor information for an attachment position.
     *
     * For START, returns the source point and direction from source toward the next route point.
     * For END, returns the target point and direction from target toward the previous route point.
     * For MIDDLE, returns the midpoint and direction along the middle segment.
     *
     * @param position The attachment position (START, END, or MIDDLE)
     * @param routeResult The route data
     * @returns The anchor point, direction vector, and optional anchor side, or undefined if not computable
     */
    private getAnchorInfo(
        position: EdgeAttachmentPosition,
        routeResult: RouteComputationResult
    ): { anchorPoint: Point; dx: number; dy: number; anchorSide?: AnchorSide } | undefined {
        const route = routeResult.route;
        if (route.length < 2) {
            return undefined;
        }

        // Normalize label positions to their equivalent base positions for anchor computation
        let basePosition: EdgeAttachmentPosition = position;
        if (position === EdgeAttachmentPosition.SOURCE_LEFT || position === EdgeAttachmentPosition.SOURCE_RIGHT) {
            basePosition = EdgeAttachmentPosition.START;
        } else if (
            position === EdgeAttachmentPosition.TARGET_LEFT ||
            position === EdgeAttachmentPosition.TARGET_RIGHT
        ) {
            basePosition = EdgeAttachmentPosition.END;
        } else if (
            position === EdgeAttachmentPosition.MIDDLE_LEFT ||
            position === EdgeAttachmentPosition.MIDDLE_RIGHT
        ) {
            basePosition = EdgeAttachmentPosition.MIDDLE;
        }

        let anchorPoint: Point;
        let dx: number;
        let dy: number;
        let anchorSide: AnchorSide | undefined;

        if (basePosition === EdgeAttachmentPosition.START) {
            anchorPoint = route[0];
            anchorSide = routeResult.sourceAnchor?.side;
            if (anchorSide) {
                const dir = this.getDirectionFromSide(anchorSide);
                dx = dir.x;
                dy = dir.y;
            } else {
                dx = route[1].x - route[0].x;
                dy = route[1].y - route[0].y;
            }
        } else if (basePosition === EdgeAttachmentPosition.END) {
            anchorPoint = route[route.length - 1];
            anchorSide = routeResult.targetAnchor?.side;
            if (anchorSide) {
                const dir = this.getDirectionFromSide(anchorSide);
                dx = dir.x;
                dy = dir.y;
            } else {
                dx = route[route.length - 2].x - route[route.length - 1].x;
                dy = route[route.length - 2].y - route[route.length - 1].y;
            }
        } else if (basePosition === EdgeAttachmentPosition.MIDDLE) {
            const middleIndex = findMiddleSegment(route);
            anchorPoint = PointUtil.linear(route[middleIndex], route[middleIndex + 1], 0.5);
            const directionPoint = route[middleIndex + 1];
            dx = directionPoint.x - anchorPoint.x;
            dy = directionPoint.y - anchorPoint.y;
        } else {
            return undefined;
        }

        return { anchorPoint, dx, dy, anchorSide };
    }

    /**
     * Gets the direction vector from an anchor side.
     *
     * @param side The anchor side
     * @returns The direction vector
     */
    private getDirectionFromSide(side: AnchorSide): Point {
        switch (side) {
            case "top":
                return { x: 0, y: -1 };
            case "bottom":
                return { x: 0, y: 1 };
            case "left":
                return { x: -1, y: 0 };
            case "right":
                return { x: 1, y: 0 };
        }
    }

    /**
     * Returns true if the position is a label attachment position
     * (SOURCE_LEFT/RIGHT, TARGET_LEFT/RIGHT, MIDDLE_LEFT/RIGHT).
     *
     * @param position The attachment position to check
     * @returns true if this is a label position
     */
    private static isLabelPosition(position: EdgeAttachmentPosition): boolean {
        return (
            position === EdgeAttachmentPosition.SOURCE_LEFT ||
            position === EdgeAttachmentPosition.SOURCE_RIGHT ||
            position === EdgeAttachmentPosition.TARGET_LEFT ||
            position === EdgeAttachmentPosition.TARGET_RIGHT ||
            position === EdgeAttachmentPosition.MIDDLE_LEFT ||
            position === EdgeAttachmentPosition.MIDDLE_RIGHT
        );
    }

    /**
     * Returns true if the position is a source-end label position (SOURCE_LEFT or SOURCE_RIGHT).
     *
     * @param position The attachment position to check
     * @returns true if this is a source label position
     */
    private static isSourceLabelPosition(position: EdgeAttachmentPosition): boolean {
        return position === EdgeAttachmentPosition.SOURCE_LEFT || position === EdgeAttachmentPosition.SOURCE_RIGHT;
    }

    /**
     * Returns true if the position is a target-end label position (TARGET_LEFT or TARGET_RIGHT).
     *
     * @param position The attachment position to check
     * @returns true if this is a target label position
     */
    private static isTargetLabelPosition(position: EdgeAttachmentPosition): boolean {
        return position === EdgeAttachmentPosition.TARGET_LEFT || position === EdgeAttachmentPosition.TARGET_RIGHT;
    }

    /**
     * Determines whether the attachment position is on the left or above side of the edge.
     *
     * Left/above side: SOURCE_LEFT, TARGET_LEFT, MIDDLE_LEFT.
     * Right/below side: SOURCE_RIGHT, TARGET_RIGHT, MIDDLE_RIGHT.
     *
     * @param position A label attachment position
     * @returns true if the attachment should be placed on the left/above side
     */
    private static determineAttachmentSide(position: EdgeAttachmentPosition): boolean {
        return (
            position === EdgeAttachmentPosition.SOURCE_LEFT ||
            position === EdgeAttachmentPosition.TARGET_LEFT ||
            position === EdgeAttachmentPosition.MIDDLE_LEFT
        );
    }

    /**
     * Stacks and renders context action rail attachments (START, END, MIDDLE) at the computed position.
     *
     * For START/END: the rail is placed in the direction opposite to where the edge is travelling,
     * centered on the edge axis. No distanceFromElement offset is applied — the raw route endpoint is used.
     * For MIDDLE: visual attachments go on one side (left/above by default),
     * context action rails (oppositeMiddleSide=true) go on the opposite side.
     *
     * @param attachments The attachments to stack
     * @param anchorPoint The anchor point on the edge
     * @param position The context rail position (START, END, or MIDDLE)
     * @param anchorInfo Direction and side information for the anchor
     * @returns An array of VNodes for the stacked attachments
     */
    private stackAttachments(
        attachments: EdgeAttachment[],
        anchorPoint: Point,
        position: EdgeAttachmentPosition,
        anchorInfo: { dx: number; dy: number; anchorSide?: AnchorSide }
    ): VNode[] {
        const vnodes: VNode[] = [];
        if (attachments.length === 0) {
            return vnodes;
        }

        const isEdgeHorizontal = Math.abs(anchorInfo.dx) > Math.abs(anchorInfo.dy);
        const distanceToLine = this.attachmentDistanceToLine;
        const isMiddle = position === EdgeAttachmentPosition.MIDDLE;

        const edgeDirSign = isEdgeHorizontal ? (anchorInfo.dx >= 0 ? 1 : -1) : anchorInfo.dy >= 0 ? 1 : -1;

        for (const attachment of attachments) {
            const width = attachment.bounds.width;
            const height = attachment.bounds.height;

            let centerX: number;
            let centerY: number;

            if (isMiddle) {
                const defaultSideSign = -1;
                const sideSign = attachment.oppositeMiddleSide ? -defaultSideSign : defaultSideSign;

                if (isEdgeHorizontal) {
                    centerX = anchorPoint.x;
                    centerY = anchorPoint.y + sideSign * (distanceToLine + height / 2);
                } else {
                    centerX = anchorPoint.x + sideSign * (distanceToLine + width / 2);
                    centerY = anchorPoint.y;
                }
            } else {
                const oppositeSign = -edgeDirSign;

                if (isEdgeHorizontal) {
                    centerX = anchorPoint.x + oppositeSign * (distanceToLine + width / 2);
                    centerY = anchorPoint.y;
                } else {
                    centerX = anchorPoint.x;
                    centerY = anchorPoint.y + oppositeSign * (distanceToLine + height / 2);
                }
            }

            const finalX = centerX - width / 2;
            const finalY = centerY - height / 2;

            if (attachment.renderAt != undefined) {
                const rendered = attachment.renderAt(
                    { x: finalX, y: finalY },
                    attachment.orientation ?? (isEdgeHorizontal ? "horizontal" : "vertical")
                );
                if (rendered != undefined) {
                    vnodes.push(rendered);
                }
            } else {
                const transformedGroup = svg(
                    "g",
                    {
                        attrs: {
                            transform: `translate(${finalX}, ${finalY})`
                        }
                    },
                    attachment.vnode
                );

                vnodes.push(transformedGroup);
            }
        }

        return vnodes;
    }

    /**
     * Adjusts the route points based on marker stroke offsets.
     *
     * @param route The original route points
     * @param startOffset The offset to apply at the start
     * @param endOffset The offset to apply at the end
     * @returns The adjusted route
     */
    private adjustRouteForMarkers(route: Point[], startOffset: number, endOffset: number): Point[] {
        if (route.length < 2) {
            return route;
        }

        const adjusted = [...route];

        if (startOffset > 0) {
            const dx = route[1].x - route[0].x;
            const dy = route[1].y - route[0].y;
            const len = Math.sqrt(dx * dx + dy * dy);
            if (len > 0) {
                adjusted[0] = {
                    x: route[0].x + (dx / len) * startOffset,
                    y: route[0].y + (dy / len) * startOffset
                };
            }
        }

        if (endOffset > 0) {
            const lastIdx = route.length - 1;
            const dx = route[lastIdx].x - route[lastIdx - 1].x;
            const dy = route[lastIdx].y - route[lastIdx - 1].y;
            const len = Math.sqrt(dx * dx + dy * dy);
            if (len > 0) {
                adjusted[lastIdx] = {
                    x: route[lastIdx].x - (dx / len) * endOffset,
                    y: route[lastIdx].y - (dy / len) * endOffset
                };
            }
        }

        return adjusted;
    }

    /**
     * Transforms and rotates a marker to align with the edge direction.
     *
     * @param marker The marker VNode
     * @param position The position point where the marker should be placed
     * @param directionPoint The point that determines the direction
     * @param type Whether this is a 'source' or 'target' marker
     * @returns The transformed marker VNode
     */
    private transformMarker(marker: VNode, position: Point, directionPoint: Point, type: "source" | "target"): VNode {
        const dx = position.x - directionPoint.x;
        const dy = position.y - directionPoint.y;
        const angle = Math.atan2(dy, dx) * (180 / Math.PI);

        const rotation = type === "source" ? angle + 180 : angle;

        return svg(
            "g",
            {
                attrs: {
                    transform: `translate(${position.x}, ${position.y}) rotate(${rotation})`
                }
            },
            marker
        );
    }

    /**
     * Renders the visible path of the edge.
     *
     * @param route The route points (adjusted for markers)
     * @param _model The edge model
     * @param _context The rendering context
     * @returns The path VNode
     */
    protected renderVisiblePath(route: Point[], _model: Readonly<GEdge>, _context: RenderingContext): VNode {
        const pathData = this.createPathData(route);

        return svg("path", {
            class: {
                "stroke-foreground": true,
                "fill-none": true,
                "stroke-[1.5px]": true
            },
            attrs: {
                d: pathData
            }
        });
    }

    /**
     * Renders the invisible selection path as individual line segments.
     * Uses non-scaling-stroke for consistent hit detection.
     * Adds data attributes for edge editing when the edge is selected.
     *
     * @param route The route points (adjusted for markers)
     * @param model The edge model
     * @returns An array of line segment VNodes
     */
    protected renderInvisiblePath(route: Point[], model: Readonly<GEdge>): VNode[] {
        const segments: VNode[] = [];
        const selected = isSelected(model);

        for (let i = 0; i < route.length - 1; i++) {
            const dx = route[i + 1].x - route[i].x;
            const dy = route[i + 1].y - route[i].y;
            const isHorizontal = Math.abs(dx) > Math.abs(dy);

            const segmentClasses: Record<string, boolean> = {
                "non-scaling-stroke": true,
                "stroke-transparent": true,
                "stroke-[10px]": true
            };

            if (selected) {
                segmentClasses[isHorizontal ? "cursor-ns-resize" : "cursor-ew-resize"] = true;
            }

            const segment = svg("line", {
                class: segmentClasses,
                attrs: {
                    x1: route[i].x,
                    y1: route[i].y,
                    x2: route[i + 1].x,
                    y2: route[i + 1].y,
                    "data-edge-segment-index": i
                }
            });
            segments.push(segment);
        }

        return segments;
    }

    /**
     * Creates an SVG path data string from route points.
     *
     * @param points The route points
     * @returns The SVG path data string
     */
    protected createPathData(points: Point[]): string {
        if (points.length === 0) {
            return "";
        }

        let path = `M ${points[0].x} ${points[0].y}`;
        for (let i = 1; i < points.length; i++) {
            path += ` L ${points[i].x} ${points[i].y}`;
        }
        return path;
    }

    /**
     * Renders reconnect handles at the source and target of the edge.
     * Only rendered when the edge is selected.
     *
     * @param model The edge model
     * @param route The computed route
     * @returns An array of VNodes for the reconnect handles
     */
    protected renderReconnectHandles(model: Readonly<GEdge>, route: Point[]): VNode[] {
        const reconnectData = model.reconnectData as EdgeReconnectData | undefined;
        const zoom = findViewportZoom(model);
        const handles: VNode[] = [];

        const sourcePoint = route[0];
        const targetPoint = route[route.length - 1];

        const sourceActive = reconnectData?.end === "source";
        const targetActive = reconnectData?.end === "target";

        handles.push(this.renderReconnectHandle(sourcePoint, "source", zoom, sourceActive ?? false));
        handles.push(this.renderReconnectHandle(targetPoint, "target", zoom, targetActive ?? false));

        return handles;
    }

    /**
     * Renders non-interactive endpoint indicators for create-edge feedback.
     * Both source and target endpoints stay visible during phase 2.
     *
     * @param model The edge model
     * @param route The computed route
     * @returns Endpoint indicator VNodes
     */
    protected renderCreateEdgeEndpoints(model: Readonly<GEdge>, route: Point[]): VNode[] {
        const zoom = findViewportZoom(model);
        const sourcePoint = route[0];
        const targetPoint = route[route.length - 1];

        return [renderCreateEdgeEndpoint(sourcePoint, zoom), renderCreateEdgeEndpoint(targetPoint, zoom)];
    }

    /**
     * Renders a single reconnect handle.
     *
     * @param position The position of the handle
     * @param end The end type (source or target)
     * @param zoom The current zoom level
     * @param isActive Whether this handle is currently being dragged
     * @returns The VNode for the handle
     */
    protected renderReconnectHandle(position: Point, end: "source" | "target", zoom: number, isActive: boolean): VNode {
        const outerRadius = GEdgeView.RECONNECT_HANDLE_SIZE / 2 / zoom;
        const innerRadius = GEdgeView.RECONNECT_HANDLE_INNER_SIZE / 2 / zoom;

        return svg(
            "g",
            {},
            svg("circle", {
                attrs: {
                    cx: position.x,
                    cy: position.y,
                    r: outerRadius,
                    "data-reconnect-handle": end
                },
                class: {
                    "fill-transparent": !isActive,
                    "fill-primary/50": isActive,
                    "hover:fill-primary/50": true,
                    "non-scaling-stroke": true,
                    "cursor-pointer": true
                }
            }),
            svg("circle", {
                attrs: {
                    cx: position.x,
                    cy: position.y,
                    r: innerRadius,
                    "data-reconnect-handle": end
                },
                class: {
                    "fill-background": true,
                    "stroke-primary": true,
                    "stroke-[1.5px]": true,
                    "non-scaling-stroke": true,
                    "pointer-events-none": true
                }
            })
        );
    }
}

/**
 * Renders a single non-interactive endpoint indicator.
 *
 * @param position Endpoint position
 * @param zoom Current zoom level
 * @returns The endpoint indicator VNode
 */
export function renderCreateEdgeEndpoint(position: Point, zoom: number): VNode {
    const outerRadius = GEdgeView.RECONNECT_HANDLE_SIZE / 2 / zoom;
    const innerRadius = GEdgeView.RECONNECT_HANDLE_INNER_SIZE / 2 / zoom;

    return svg(
        "g",
        {},
        svg("circle", {
            attrs: {
                cx: position.x,
                cy: position.y,
                r: outerRadius
            },
            class: {
                "fill-primary/25": true,
                "non-scaling-stroke": true,
                "pointer-events-none": true
            }
        }),
        svg("circle", {
            attrs: {
                cx: position.x,
                cy: position.y,
                r: innerRadius
            },
            class: {
                "fill-background": true,
                "stroke-primary": true,
                "stroke-[1.5px]": true,
                "non-scaling-stroke": true,
                "pointer-events-none": true
            }
        })
    );
}

/**
 * Collects the text of all text labels below an element (used to detect identical edge labels).
 *
 * @param element the element
 * @returns the concatenated texts
 */
function collectLabelText(element: GModelElement): string {
    const texts: string[] = [];
    const visit = (current: GModelElement) => {
        const text = (current as { text?: unknown }).text;
        if (typeof text === "string") {
            texts.push(text);
        }
        const children = (current as { children?: readonly GModelElement[] }).children ?? [];
        children.forEach(visit);
    };
    visit(element);
    return texts.join(" ");
}

/**
 * Checks whether an element or one of its descendants is a label in edit mode.
 *
 * @param element the element
 * @returns true if a label is being edited
 */
function isInEditMode(element: GModelElement): boolean {
    if ((element as { editMode?: unknown }).editMode === true) {
        return true;
    }
    const children = (element as { children?: readonly GModelElement[] }).children ?? [];
    return children.some(isInEditMode);
}
