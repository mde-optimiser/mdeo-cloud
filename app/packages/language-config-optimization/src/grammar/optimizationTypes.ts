import {
    createInterface,
    createType,
    createExternalInterface,
    Optional,
    Ref,
    Union,
    type ASTType
} from "@mdeo/language-common";
import { FileScopingConfig, generateImportTypes } from "@mdeo/language-shared";
import type { AssociationEndType, ClassType, PropertyType } from "@mdeo/language-metamodel";
import type { FunctionType } from "@mdeo/language-script";

/**
 * External reference to ScriptFunction interface from language-script.
 * Used for referencing functions in constraints and objectives.
 */
export const ScriptFunction = createExternalInterface<FunctionType>("ScriptFunction");

/**
 * External reference to Class interface from language-metamodel.
 * Used for referencing classes in refinements.
 */
export const MetamodelClass = createExternalInterface<ClassType>("Class");

/**
 * External reference to Property interface from language-metamodel.
 * Used for referencing properties in refinements.
 */
export const Property = createExternalInterface<PropertyType>("Property");

/**
 * External reference to AssociationEnd interface from language-metamodel
 * Used for referencing association ends in refinements
 */
export const AssociationEnd = createExternalInterface<AssociationEndType>("AssociationEnd");

/**
 * Union type for property or association end references in refinements.
 */
export const PropertyOrAssociationEnd = createType("PropertyOrAssociationEnd").types(Property, AssociationEnd);

/**
 * Single multiplicity specification.
 * Can be a numeric value or one of the special symbols: * (any), + (one or more), ? (optional).
 */
export const SingleMultiplicity = createInterface("ConfigSingleMultiplicity").attrs({
    value: Optional(Union("*", "+", "?")),
    numericValue: Optional(Number)
});

/**
 * Type representing a SingleMultiplicity AST node.
 */
export type SingleMultiplicityType = ASTType<typeof SingleMultiplicity>;

/**
 * Range multiplicity specification.
 * Defines a lower bound and upper bound (can be * for unlimited).
 */
export const RangeMultiplicity = createInterface("ConfigRangeMultiplicity").attrs({
    lower: Number,
    upper: Union("*"),
    upperNumeric: Optional(Number)
});

/**
 * Type representing a RangeMultiplicity AST node.
 */
export type RangeMultiplicityType = ASTType<typeof RangeMultiplicity>;

/**
 * Union type for multiplicity specifications.
 * Can be either single or range multiplicity.
 */
export const Multiplicity = createType("ConfigMultiplicity").types(SingleMultiplicity, RangeMultiplicity);

/**
 * Type representing a Multiplicity AST node.
 */
export type MultiplicityType = ASTType<typeof Multiplicity>;

/**
 * Configuration for file-scoped function imports in the optimization config.
 * Uses the shared file-scoping pattern from language-shared.
 */
export const configOptimizationFileScopingConfig = new FileScopingConfig<FunctionType>(
    "ConfigOptimizationFunction",
    ScriptFunction
);

/**
 * Generated import types for functions using the file-scoping pattern.
 */
export const { importType: FunctionImport, fileImportType: FunctionFileImport } = generateImportTypes(
    configOptimizationFileScopingConfig
);

/**
 * Type representing a FunctionImport AST node.
 */
export type FunctionImportType = ASTType<typeof FunctionImport>;

/**
 * Type representing a FunctionFileImport AST node.
 */
export type FunctionFileImportType = ASTType<typeof FunctionFileImport>;

/**
 * Problem section interface.
 * Defines the metamodel and model file paths for the optimization problem.
 */
export const ProblemSection = createInterface("ConfigProblemSection").attrs({
    metamodel: [String],
    model: [String]
});

/**
 * Type representing a ProblemSection AST node.
 */
export type ProblemSectionType = ASTType<typeof ProblemSection>;

/**
 * Constraint reference.
 * References a function that returns a numeric type representing the magnitude of constraint
 * violation. A return value of 0.0 means the constraint is satisfied; any non-zero value
 * represents the degree of violation.
 */
export const ConstraintReference = createInterface("ConfigConstraintReference").attrs({
    constraint: Ref(() => ScriptFunction)
});

/**
 * Type representing a ConstraintReference AST node.
 */
export type ConstraintReferenceType = ASTType<typeof ConstraintReference>;

/**
 * Objective definition.
 * Specifies whether to maximize or minimize a function that returns a numeric type.
 */
export const Objective = createInterface("ConfigObjective").attrs({
    type: Union("maximize", "minimize"),
    objective: Ref(() => ScriptFunction)
});

/**
 * Type representing an Objective AST node.
 */
export type ObjectiveType = ASTType<typeof Objective>;

/**
 * Refinement definition.
 * Specifies a class field that can be refined with a specific multiplicity.
 * Format: refine ClassRef.fieldRef[multiplicity]
 */
export const Refinement = createInterface("ConfigRefinement").attrs({
    class: Ref(() => MetamodelClass),
    field: Ref(() => PropertyOrAssociationEnd),
    multiplicity: Multiplicity
});

/**
 * Type representing a Refinement AST node.
 */
export type RefinementType = ASTType<typeof Refinement>;

/**
 * Goal section interface.
 * Contains function imports, constraints, objectives, and refinements.
 */
export const GoalSection = createInterface("ConfigGoalSection").attrs({
    imports: [FunctionFileImport],
    constraints: [ConstraintReference],
    objectives: [Objective],
    refinements: [Refinement]
});

/**
 * Type representing a GoalSection AST node.
 */
export type GoalSectionType = ASTType<typeof GoalSection>;
