import type { IView, RenderingContext } from "@eclipse-glsp/sprotty";
import type { GIssueMarker } from "@eclipse-glsp/client";
import type { IconNode } from "lucide";
import type { VNode } from "snabbdom";
import { sharedImport } from "../sharedImport.js";
import { findViewportZoom } from "../base/findViewportZoom.js";
import { ISSUE_MARKER_SIZE } from "../features/decoration/issueMarker.js";

const { injectable } = sharedImport("inversify");
const { svg } = sharedImport("@eclipse-glsp/sprotty");
const { getSeverity } = sharedImport("@eclipse-glsp/client");
const { CircleX, TriangleAlert, Info } = sharedImport("lucide");

/**
 * Display configuration for a given issue severity level.
 */
interface SeverityConfig {
    /**
     * Lucide icon node rendered inside the badge.
     */
    icon: IconNode;
    /**
     * Tailwind `text-*` class applied to the outer SVG group.
     * Sets `currentColor` for all child SVG elements that use `stroke="currentColor"`.
     */
    colorClass: string;
}

/**
 * View registered for the `"marker"` GLSP element type that renders the issue badge.
 *
 * The badge is a severity-coloured Lucide icon on a filled background circle, uniformly
 * scaled so the rendered size is always exactly {@link ISSUE_MARKER_SIZE} CSS pixels
 * regardless of the current diagram zoom.
 *
 * The group is rendered at the local origin (0, 0) and is `pointer-events-none`; hover
 * interaction for the popup is managed by {@link IssueMarkerUIExtension}.  The parent
 * node/edge view is responsible for translating the returned group into place.
 *
 * @see IssueMarkerUIExtension
 */
@injectable()
export class CustomGIssueMarkerView implements IView {
    /**
     * Returns the display configuration (icon + color class) for the given severity.
     *
     * @param severity The severity string — one of `"error"`, `"warning"`, or `"info"`.
     * @returns The {@link SeverityConfig} for the severity, defaulting to `"info"` config.
     */
    private getSeverityConfig(severity: string): SeverityConfig {
        switch (severity) {
            case "error":
                return { icon: CircleX, colorClass: "text-destructive" };
            case "warning":
                return { icon: TriangleAlert, colorClass: "text-chart-5" };
            case "info":
            default:
                return { icon: Info, colorClass: "text-sidebar-primary" };
        }
    }

    /**
     * Converts a Lucide {@link IconNode} into SVG element VNodes within the Lucide 24×24
     * coordinate space.  All paths use `stroke="currentColor"` so they inherit the
     * severity color from the parent group's Tailwind `text-*` class.
     *
     * @param iconNode The Lucide icon descriptor array to convert.
     * @returns An array of SVG element VNodes for the icon's paths and shapes.
     */
    private renderLucideIconSvg(iconNode: IconNode): VNode[] {
        return iconNode.map(([tag, attrs]) =>
            svg(tag, {
                attrs: {
                    ...Object.fromEntries(Object.entries(attrs).filter(([, v]) => v != null)),
                    stroke: "currentColor",
                    fill: "none",
                    "stroke-width": 2,
                    "stroke-linecap": "round",
                    "stroke-linejoin": "round"
                }
            })
        );
    }

    /**
     * Renders the issue badge at the local origin (0, 0), scaled for the current zoom.
     *
     * The group occupies `[0, 0, ISSUE_MARKER_SIZE/zoom, ISSUE_MARKER_SIZE/zoom]` in
     * local SVG coordinates; callers are responsible for translating it into place.
     *
     * A transparent `<rect>` is included as the topmost child so that GLSP mouse
     * listeners receive pointer events over the badge area (the icon paths are still
     * `pointer-events-none` to avoid spurious hits on individual strokes).
     *
     * @param model The marker model element to render
     * @param _context The rendering context (unused)
     * @returns An SVG `<g>` VNode for the badge, or `undefined` when there are no issues
     */
    render(model: Readonly<GIssueMarker>, _context: RenderingContext): VNode | undefined {
        if (model.issues.length === 0) {
            return undefined;
        }

        const zoom = findViewportZoom(model);
        const severity = getSeverity(model);
        const { icon, colorClass } = this.getSeverityConfig(severity);

        const svgSize = ISSUE_MARKER_SIZE / zoom;
        const scale = svgSize / 24;

        const background = svg("circle", {
            attrs: { cx: 12, cy: 12, r: 11.5, stroke: "currentColor", "stroke-width": 1 },
            class: { "fill-background": true }
        });

        return svg(
            "g",
            {
                class: {
                    [colorClass]: true,
                    "pointer-events-none": true
                }
            },
            svg("g", { attrs: { transform: `scale(${scale})` } }, background, ...this.renderLucideIconSvg(icon)),
            // Transparent hit-area rect so pointer events reach GLSP mouse listeners.
            svg("rect", {
                attrs: { x: 0, y: 0, width: svgSize, height: svgSize, fill: "transparent" },
                class: { "pointer-events-auto": true }
            })
        );
    }
}
