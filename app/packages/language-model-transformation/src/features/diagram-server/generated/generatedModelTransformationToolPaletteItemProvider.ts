import type { Args, PaletteItem } from "@eclipse-glsp/protocol";
import { BaseToolPaletteItemProvider, sharedImport } from "@mdeo/language-shared";

const { injectable } = sharedImport("inversify");

/**
 * Empty tool palette provider for generated model transformation diagrams.
 */
@injectable()
export class GeneratedModelTransformationToolPaletteItemProvider extends BaseToolPaletteItemProvider {
    override async getItems(_args?: Args): Promise<PaletteItem[]> {
        return [];
    }
}
