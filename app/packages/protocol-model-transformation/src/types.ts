/**
 * Type constants for model transformation diagram elements, shared between editor and language packages.
 */
export enum ModelTransformationElementType {
    /**
     * Start node of the transformation
     */
    NODE_START = "node:start",
    /**
     * End node (stop or kill)
     */
    NODE_END = "node:end",
    /**
     * Match node containing pattern elements
     */
    NODE_MATCH = "node:match",
    /**
     * Split node for if/while expression branching
     */
    NODE_SPLIT = "node:split",
    /**
     * Merge node where branches join back together
     */
    NODE_MERGE = "node:merge",
    /**
     * Control flow edge connecting nodes
     */
    EDGE_CONTROL_FLOW = "edge:control-flow",
    /**
     * Node wrapping a control flow edge label
     */
    NODE_CONTROL_FLOW_LABEL = "node:control-flow-label",
    /**
     * Label on a control flow edge
     */
    LABEL_CONTROL_FLOW = "label:control-flow",
    /**
     * Pattern object instance node
     */
    NODE_PATTERN_INSTANCE = "node:pattern-instance",
    /**
     * Label displaying the pattern instance name and optional type
     */
    LABEL_PATTERN_INSTANCE_NAME = "label:pattern-instance-name",
    /**
     * Label displaying a property assignment in a pattern
     */
    LABEL_PATTERN_PROPERTY = "label:pattern-property",
    /**
     * Edge representing a pattern link between instances
     */
    EDGE_PATTERN_LINK = "edge:pattern-link",
    /**
     * Node wrapping a pattern link end label
     */
    NODE_PATTERN_LINK_END = "node:pattern-link-end",
    /**
     * Label at the end of a pattern link
     */
    LABEL_PATTERN_LINK_END = "label:pattern-link-end",
    /**
     * Label displaying a where clause condition
     */
    LABEL_WHERE_CLAUSE = "label:where-clause",
    /**
     * Label displaying a variable declaration
     */
    LABEL_VARIABLE = "label:variable",
    /**
     * Modifier label shown in the middle of a pattern link edge (create/delete/forbid/require)
     */
    LABEL_PATTERN_LINK_MODIFIER = "label:pattern-link-modifier",
    /**
     * Node wrapping a pattern link modifier label
     */
    NODE_PATTERN_LINK_MODIFIER = "node:pattern-link-modifier",
    /**
     * Label displaying a pattern instance modifier (create/delete/forbid/require)
     */
    LABEL_PATTERN_MODIFIER = "label:pattern-modifier",
    /**
     * Horizontal divider line between compartments
     */
    DIVIDER = "divider:horizontal",
    /**
     * Compartment container for grouping elements
     */
    COMPARTMENT = "comp:compartment",
    /**
     * Container wrapping the bottom compartments of a match node (where-clauses, variables)
     */
    MATCH_NODE_COMPARTMENTS = "comp:match-node-compartments",
    /**
     * Modifier title compartment (renders «create», «delete», «forbid», «require» + name label)
     */
    COMPARTMENT_MODIFIER_TITLE = "comp:modifier-title"
}

/**
 * Enum for pattern modifier kinds.
 *
 * `CREATE` and `DELETE` are element modifiers written in the source. `FORBID` and
 * `REQUIRE` are not: they describe membership in an application condition block and are
 * derived from the block an element is declared in, so that the diagram can render the
 * element with the block's stereotype.
 * Use NONE when the element belongs to the match pattern and carries no modifier.
 */
export enum PatternModifierKind {
    /**
     * No modifier (match only)
     */
    NONE = "none",
    /**
     * Create the element
     */
    CREATE = "create",
    /**
     * Delete the element
     */
    DELETE = "delete",
    /**
     * The element belongs to a negative application condition block
     */
    FORBID = "forbid",
    /**
     * The element belongs to a positive application condition block
     */
    REQUIRE = "require"
}

/**
 * Enum for end node kinds.
 * Represents whether this is a stop or kill termination.
 */
export enum EndNodeKind {
    /**
     * Stop execution normally
     */
    STOP = "stop",
    /**
     * Kill execution (terminate immediately)
     */
    KILL = "kill"
}

/**
 * The mode the model transformation toolbox creates pattern elements in.
 *
 * The value is chosen in the editor and travels to the server as the `mode` of a toolbox
 * request or of the create-edge context. Its values coincide with the modifier keywords of
 * the grammar, except for {@link NodeCreationMode.PERSIST}, which stands for no modifier at
 * all, and for {@link NodeCreationMode.REQUIRE} / {@link NodeCreationMode.FORBID}, which are
 * no longer element modifiers: an element created in one of those modes goes into an
 * application condition block of its own.
 */
export enum NodeCreationMode {
    /** No modifier — the element is matched as it is. */
    PERSIST = "persist",
    /** The element is created by the transformation. */
    CREATE = "create",
    /** The element is deleted by the transformation. */
    DELETE = "delete",
    /** The element goes into a `require` block. */
    REQUIRE = "require",
    /** The element goes into a `forbid` block. */
    FORBID = "forbid"
}

/**
 * The create-edge context the model transformation editor sends with a connection request.
 */
export interface PatternLinkCreationContext {
    /** The creation mode selected in the toolbox. */
    mode?: NodeCreationMode;
}

/**
 * The icons the model transformation editor draws itself, rather than taking them from the
 * icon library.
 *
 * A context item names its icon on the server and the editor's icon registry resolves the
 * name; a name that neither side agrees on renders nothing, so both sides read it from here.
 */
export enum ModelTransformationIcon {
    /** A plus sign, marking the addition of a variable. */
    VARIABLE_PLUS = "variable-plus"
}
