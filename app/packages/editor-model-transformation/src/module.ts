import type { BindingContext } from "@eclipse-glsp/sprotty";
import {
    sharedImport,
    GCompartmentView,
    GLabelView,
    configureDefaultModelElements,
    GCompartment,
    GHorizontalDivider,
    CreateEdgeContextProvider
} from "@mdeo/editor-shared";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import { GPatternInstanceNode } from "./model/patternInstanceNode.js";
import { GPatternModifierTitleCompartment } from "./model/patternModifierTitleCompartment.js";
import { GPatternInstanceNameLabel } from "./model/patternInstanceNameLabel.js";
import { GPatternPropertyLabel } from "./model/patternPropertyLabel.js";
import { GPatternLinkEdge } from "./model/patternLinkEdge.js";
import { GPatternLinkEndNode } from "./model/patternLinkEndNode.js";
import { GPatternLinkEndLabel } from "./model/patternLinkEndLabel.js";
import { GStartNode } from "./model/startNode.js";
import { GEndNode } from "./model/endNode.js";
import { GSplitNode } from "./model/splitNode.js";
import { GCallLabel, GCallNode } from "./model/callNode.js";
import { GMergeNode } from "./model/mergeNode.js";
import { GControlFlowEdge } from "./model/controlFlowEdge.js";
import { GControlFlowLabelNode } from "./model/controlFlowLabelNode.js";
import { GControlFlowLabel } from "./model/controlFlowLabel.js";
import { GPatternInstanceNodeView } from "./views/patternInstanceNodeView.js";
import { GPatternModifierTitleCompartmentView } from "./views/patternModifierTitleCompartmentView.js";
import { GPatternInstanceLabelView } from "./views/patternInstanceLabelView.js";
import { GPatternPropertyLabelView } from "./views/patternPropertyLabelView.js";
import { GPatternInstanceDividerView } from "./views/patternInstanceDividerView.js";
import { GPatternLinkEdgeView } from "./views/patternLinkEdgeView.js";
import { GPatternLinkEndNodeView } from "./views/patternLinkEndNodeView.js";
import { GPatternLinkEndLabelView } from "./views/patternLinkEndLabelView.js";
import { GStartNodeView } from "./views/startNodeView.js";
import { GEndNodeView } from "./views/endNodeView.js";
import { GSplitNodeView } from "./views/splitNodeView.js";
import { GCallNodeView } from "./views/callNodeView.js";
import { GMergeNodeView } from "./views/mergeNodeView.js";
import { GControlFlowEdgeView } from "./views/controlFlowEdgeView.js";
import { GControlFlowLabelNodeView } from "./views/controlFlowLabelNodeView.js";
import { GMatchNode } from "./model/matchNode.js";
import { GMatchNodeCompartments } from "./model/matchNodeCompartments.js";
import { GVariableLabel } from "./model/variableLabel.js";
import { GWhereClauseLabel } from "./model/whereClauseLabel.js";
import { GMatchNodeView } from "./views/matchNodeView.js";
import { GMatchNodeCompartmentsView } from "./views/matchNodeCompartmentsView.js";
import { GPatternLinkModifierLabel } from "./model/patternLinkModifierLabel.js";
import { GPatternLinkModifierNode } from "./model/patternLinkModifierNode.js";
import { GPatternModifierLabel } from "./model/patternModifierLabel.js";
import { GPatternLinkModifierLabelView } from "./views/patternLinkModifierLabelView.js";
import { GPatternLinkModifierNodeView } from "./views/patternLinkModifierNodeView.js";
import { GWhereClauseLabelView } from "./views/whereClauseLabelView.js";
import { GVariableLabelView } from "./views/variableLabelView.js";
import { GPatternModifierLabelView } from "./views/patternModifierLabelView.js";
import { PatternLinkContextProvider } from "./features/create-edge/patternLinkContextProvider.js";
import { GDeclarationLabel } from "./model/declarationLabel.js";
import { GDeclarationLabelView } from "./views/declarationLabelView.js";

const { FeatureModule, configureModelElement } = sharedImport("@eclipse-glsp/sprotty");

/**
 * Feature module for the model transformation editor.
 * Configures the transformation-specific diagram elements and their views.
 */
export const modelTransformationDiagramModule = new FeatureModule(
    (bind, unbind, isBound, rebind) => {
        const context: BindingContext = { bind, isBound, unbind, rebind };

        configureDefaultModelElements(context);

        bind(CreateEdgeContextProvider).to(PatternLinkContextProvider).inSingletonScope();

        configureModelElement(context, ModelTransformationElementType.COMPARTMENT, GCompartment, GCompartmentView);

        configureModelElement(
            context,
            ModelTransformationElementType.DIVIDER,
            GHorizontalDivider,
            GPatternInstanceDividerView
        );

        configureModelElement(
            context,
            ModelTransformationElementType.NODE_PATTERN_INSTANCE,
            GPatternInstanceNode,
            GPatternInstanceNodeView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.COMPARTMENT_MODIFIER_TITLE,
            GPatternModifierTitleCompartment,
            GPatternModifierTitleCompartmentView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_PATTERN_INSTANCE_NAME,
            GPatternInstanceNameLabel,
            GPatternInstanceLabelView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_PATTERN_PROPERTY,
            GPatternPropertyLabel,
            GPatternPropertyLabelView
        );

        configureModelElement(
            context,
            ModelTransformationElementType.EDGE_PATTERN_LINK,
            GPatternLinkEdge,
            GPatternLinkEdgeView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.NODE_PATTERN_LINK_END,
            GPatternLinkEndNode,
            GPatternLinkEndNodeView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_PATTERN_LINK_END,
            GPatternLinkEndLabel,
            GPatternLinkEndLabelView
        );

        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_DECLARATION,
            GDeclarationLabel,
            GDeclarationLabelView
        );
        configureModelElement(context, ModelTransformationElementType.NODE_START, GStartNode, GStartNodeView);
        configureModelElement(context, ModelTransformationElementType.NODE_END, GEndNode, GEndNodeView);
        configureModelElement(context, ModelTransformationElementType.NODE_SPLIT, GSplitNode, GSplitNodeView);
        configureModelElement(context, ModelTransformationElementType.NODE_MERGE, GMergeNode, GMergeNodeView);
        configureModelElement(context, ModelTransformationElementType.NODE_CALL, GCallNode, GCallNodeView);
        configureModelElement(context, ModelTransformationElementType.LABEL_CALL, GCallLabel, GLabelView);
        configureModelElement(
            context,
            ModelTransformationElementType.EDGE_CONTROL_FLOW,
            GControlFlowEdge,
            GControlFlowEdgeView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.NODE_CONTROL_FLOW_LABEL,
            GControlFlowLabelNode,
            GControlFlowLabelNodeView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_CONTROL_FLOW,
            GControlFlowLabel,
            GLabelView
        );

        configureModelElement(context, ModelTransformationElementType.NODE_MATCH, GMatchNode, GMatchNodeView);
        configureModelElement(
            context,
            ModelTransformationElementType.MATCH_NODE_COMPARTMENTS,
            GMatchNodeCompartments,
            GMatchNodeCompartmentsView
        );

        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_VARIABLE,
            GVariableLabel,
            GVariableLabelView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_WHERE_CLAUSE,
            GWhereClauseLabel,
            GWhereClauseLabelView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_PATTERN_LINK_MODIFIER,
            GPatternLinkModifierLabel,
            GPatternLinkModifierLabelView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.NODE_PATTERN_LINK_MODIFIER,
            GPatternLinkModifierNode,
            GPatternLinkModifierNodeView
        );
        configureModelElement(
            context,
            ModelTransformationElementType.LABEL_PATTERN_MODIFIER,
            GPatternModifierLabel,
            GPatternModifierLabelView
        );
    },
    { featureId: Symbol("modelTransformationDiagram") }
);
