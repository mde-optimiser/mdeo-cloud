/**
 * Type constants for metamodel diagram elements, shared between editor and language packages.
 */
export enum MetamodelElementType {
    NODE_CLASS = "node:class",
    NODE_ENUM = "node:enum",
    NODE_ASSOCIATION_PROPERTY = "node:association-property",
    NODE_ASSOCIATION_MULTIPLICITY = "node:association-multiplicity",
    LABEL_CLASS_NAME = "label:class-name",
    LABEL_ENUM_NAME = "label:enum-name",
    LABEL_ENUM_ENTRY = "label:enum-entry",
    LABEL_PROPERTY = "label:property",
    LABEL_ASSOCIATION_PROPERTY = "label:association-property",
    LABEL_ASSOCIATION_MULTIPLICITY = "label:association-multiplicity",
    LABEL_ASSOCIATION_END = "label:association-end",
    EDGE_INHERITANCE = "edge:inheritance",
    EDGE_ASSOCIATION = "edge:association",
    COMPARTMENT = "comp:compartment",
    COMPARTMENT_ENUM_TITLE = "comp:enum-title",
    DIVIDER = "divider:horizontal"
}

/**
 * Enum for association end kinds.
 * Represents the decoration at each end of an association edge.
 */
export enum AssociationEndKind {
    /**
     * No decoration (plain line end)
     */
    NONE = "none",
    /**
     * Composition (filled diamond)
     */
    COMPOSITION = "composition",
    /**
     * Navigability (arrow)
     */
    ARROW = "arrow"
}

/**
 * The type of edge to create with the connection tool.
 *
 * The value is chosen in the editor's toolbox and travels to the server as the `edgeType`
 * of the create-edge context, where it decides which association is written.
 */
export enum EdgeCreationType {
    /** A single navigable end: `source -> target`. */
    UNIDIRECTIONAL = "unidirectional",
    /** Both ends navigable. */
    BIDIRECTIONAL = "bidirectional",
    /** Composition at the source end. */
    COMPOSITION = "composition",
    /** Composition at the source end with a navigable target. */
    NAVIGABLE_COMPOSITION = "navigable-composition",
    /** An inheritance edge rather than an association. */
    EXTENDS = "extends"
}

/**
 * The create-edge context the metamodel editor sends with a connection request.
 */
export interface MetamodelEdgeCreationContext {
    /** The edge type selected in the toolbox. */
    edgeType?: EdgeCreationType;
}

/**
 * The icons the metamodel editor draws itself, rather than taking them from the icon library.
 *
 * A context item names its icon on the server and the editor's icon registry resolves the
 * name; a name that neither side agrees on renders nothing, so both sides read it from here.
 */
export enum MetamodelIcon {
    /** A plain diagonal line, no decorator. */
    NONE_ASSOCIATION = "none-association",
    /** A diagonal line with a single arrowhead. */
    UNIDIRECTIONAL_ASSOCIATION = "unidirectional-association",
    /** A diagonal line with a filled diamond at the target end. */
    COMPOSITION = "composition"
}
