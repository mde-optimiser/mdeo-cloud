import type {
    LayoutInput,
    LayoutInputEdge,
    LayoutInputNode,
    LayoutPoint,
    LayoutResult,
    LayoutResultNode
} from "./layoutModel.js";
import { snapLayout, type SnapOptions } from "./layoutSnapping.js";

/**
 * Lays out a single, flat level of the hierarchy (nodes without parentId, edges between them).
 *
 * @param level the flat layout input of the level
 * @param containerId the container whose children are laid out, undefined for the top level
 * @returns the result, in an arbitrary coordinate system
 */
export type LevelLayoutFunction = (level: LayoutInput, containerId: string | undefined) => Promise<LayoutResult>;

/**
 * The coordinate frame of a level: local coordinates (the ones stored in the metadata of the level's
 * nodes and edges) plus origin = absolute coordinates.
 */
export interface LevelFrame {
    origin: LayoutPoint;
}

/**
 * The result of a hierarchical layout.
 */
export interface HierarchicalLayoutResult {
    /**
     * The result in absolute coordinates.
     */
    result: LayoutResult;
    /**
     * The frames of all levels: key is the container id, or {@link ROOT_LEVEL} for the top level.
     */
    frames: Map<string, LevelFrame>;
    /**
     * The level (container id or {@link ROOT_LEVEL}) of every node.
     */
    nodeLevel: Map<string, string>;
    /**
     * The level of every edge that was laid out. Edges between different levels are not laid out.
     */
    edgeLevel: Map<string, string>;
}

/**
 * Key of the top level.
 */
export const ROOT_LEVEL = "\u0000root";

/**
 * Computes the bounding box of the given nodes and the interior points of the given routes,
 * which is what the client uses to place the children of a container.
 *
 * @param result the local result
 * @param nodeIds the node ids
 * @param edgeIds the edge ids
 * @returns the bounding box, undefined if empty
 */
export function contentBounds(
    result: LayoutResult,
    nodeIds: Iterable<string>,
    edgeIds: Iterable<string>
): LayoutResultNode | undefined {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const id of nodeIds) {
        const node = result.nodes.get(id);
        if (node == undefined) continue;
        minX = Math.min(minX, node.x);
        minY = Math.min(minY, node.y);
        maxX = Math.max(maxX, node.x + node.width);
        maxY = Math.max(maxY, node.y + node.height);
    }
    for (const id of edgeIds) {
        const points = result.edges.get(id)?.points ?? [];
        for (const point of points.slice(1, -1)) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
    }
    if (minX === Infinity) {
        return undefined;
    }
    return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

/**
 * Lays out a hierarchical graph bottom up: the children of every container are laid out first (with the
 * container's padding), the container then gets the size the client will draw it at, and is laid out as a
 * fixed size node on its parent level. Every level is snapped to the grid in its own local coordinates.
 *
 * Edges must connect nodes on the same level; edges crossing container borders are skipped (the client routes them).
 *
 * @param input the layout input
 * @param layoutLevel lays out one flat level
 * @param snap the snap options
 * @returns the absolute result plus the level frames
 */
export async function layoutHierarchy(
    input: LayoutInput,
    layoutLevel: LevelLayoutFunction,
    snap: SnapOptions
): Promise<HierarchicalLayoutResult> {
    const nodeById = new Map(input.nodes.map((n) => [n.id, n]));
    const children = new Map<string, LayoutInputNode[]>();
    const nodeLevel = new Map<string, string>();
    for (const node of input.nodes) {
        const level = node.parentId != undefined && nodeById.has(node.parentId) ? node.parentId : ROOT_LEVEL;
        nodeLevel.set(node.id, level);
        if (!children.has(level)) children.set(level, []);
        children.get(level)!.push(node);
    }
    const edgesOfLevel = new Map<string, LayoutInputEdge[]>();
    const edgeLevel = new Map<string, string>();
    for (const edge of input.edges) {
        const sourceLevel = nodeLevel.get(edge.sourceId);
        const targetLevel = nodeLevel.get(edge.targetId);
        if (sourceLevel == undefined || sourceLevel !== targetLevel) {
            continue;
        }
        edgeLevel.set(edge.id, sourceLevel);
        if (!edgesOfLevel.has(sourceLevel)) edgesOfLevel.set(sourceLevel, []);
        edgesOfLevel.get(sourceLevel)!.push(edge);
    }

    const localResults = new Map<string, LayoutResult>();
    const contentMin = new Map<string, LayoutPoint>();
    const sizes = new Map<string, { width: number; height: number }>();

    const layoutContainer = async (levelId: string): Promise<void> => {
        const levelNodes = children.get(levelId) ?? [];
        for (const child of levelNodes) {
            if ((children.get(child.id)?.length ?? 0) > 0) {
                await layoutContainer(child.id);
            }
        }
        const levelEdges = edgesOfLevel.get(levelId) ?? [];
        const flatNodes: LayoutInputNode[] = levelNodes.map((node) => {
            const size = sizes.get(node.id);
            return {
                ...node,
                parentId: undefined,
                width: size?.width ?? node.width,
                height: size?.height ?? node.height
            };
        });
        const container = levelId === ROOT_LEVEL ? undefined : nodeById.get(levelId);
        const level: LayoutInput = {
            nodes: flatNodes,
            edges: levelEdges,
            direction: container != undefined ? (container.direction ?? input.direction) : input.direction
        };
        const raw = await layoutLevel(level, container?.id);
        // force the measured sizes, algorithms may change them slightly
        for (const node of flatNodes) {
            const placed = raw.nodes.get(node.id);
            if (placed != undefined) {
                raw.nodes.set(node.id, { x: placed.x, y: placed.y, width: node.width, height: node.height });
            } else {
                raw.nodes.set(node.id, { x: 0, y: 0, width: node.width, height: node.height });
            }
        }
        const snapped = snapLayout(raw, levelEdges, snap);
        localResults.set(levelId, snapped);
        if (container != undefined) {
            const bounds = contentBounds(
                snapped,
                flatNodes.map((n) => n.id),
                levelEdges.map((e) => e.id)
            ) ?? { x: 0, y: 0, width: 0, height: 0 };
            const padding = container.padding ?? { top: 0, right: 0, bottom: 0, left: 0 };
            contentMin.set(levelId, { x: bounds.x, y: bounds.y });
            sizes.set(levelId, {
                width: Math.max(container.width, bounds.width + padding.left + padding.right),
                height: Math.max(container.height, bounds.height + padding.top + padding.bottom)
            });
        }
    };

    await layoutContainer(ROOT_LEVEL);

    const frames = new Map<string, LevelFrame>();
    frames.set(ROOT_LEVEL, { origin: { x: 0, y: 0 } });
    const result: LayoutResult = { nodes: new Map(), edges: new Map() };
    const compose = (levelId: string) => {
        const local = localResults.get(levelId);
        if (local == undefined) return;
        const origin = frames.get(levelId)!.origin;
        for (const [id, node] of local.nodes) {
            const absolute = { x: node.x + origin.x, y: node.y + origin.y, width: node.width, height: node.height };
            result.nodes.set(id, absolute);
            if (localResults.has(id)) {
                const padding = nodeById.get(id)?.padding ?? { top: 0, right: 0, bottom: 0, left: 0 };
                const min = contentMin.get(id) ?? { x: 0, y: 0 };
                frames.set(id, {
                    origin: { x: absolute.x + padding.left - min.x, y: absolute.y + padding.top - min.y }
                });
                compose(id);
            }
        }
        for (const [id, edge] of local.edges) {
            if (edgeLevel.get(id) !== levelId) continue;
            result.edges.set(id, { points: edge.points.map((p) => ({ x: p.x + origin.x, y: p.y + origin.y })) });
        }
    };
    compose(ROOT_LEVEL);

    return { result, frames, nodeLevel, edgeLevel };
}
