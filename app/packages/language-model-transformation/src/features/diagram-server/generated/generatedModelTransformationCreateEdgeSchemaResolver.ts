import type { CreateEdgeSchema } from "@mdeo/protocol-common";
import { CreateEdgeSchemaResolver, sharedImport } from "@mdeo/language-shared";
import type { InitialCreateEdgeSchemaRequest, TargetCreateEdgeSchemaRequest } from "@mdeo/language-shared";

const { injectable } = sharedImport("inversify");

/**
 * Dummy create-edge resolver for generated model transformation diagrams.
 */
@injectable()
export class GeneratedModelTransformationCreateEdgeSchemaResolver extends CreateEdgeSchemaResolver {
    override async getInitialSchema(_request: InitialCreateEdgeSchemaRequest): Promise<CreateEdgeSchema | undefined> {
        throw new Error("Create-edge schema resolution is not supported in generated model transformation diagrams.");
    }

    override async getTargetSchema(_request: TargetCreateEdgeSchemaRequest): Promise<CreateEdgeSchema | undefined> {
        throw new Error("Create-edge schema resolution is not supported in generated model transformation diagrams.");
    }
}
