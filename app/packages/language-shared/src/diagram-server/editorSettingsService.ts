import { sharedImport } from "../sharedImport.js";
import type { EditorSettings } from "@mdeo/protocol-common";

const { injectable } = sharedImport("inversify");

/**
 * Server-side service that holds editor UI settings for the current diagram session.
 *
 * Settings are populated by the client during session startup (via
 * {@link UpdateEditorSettingsAction} dispatched in `preRequestModel`) and kept
 * up-to-date whenever the user changes a UI setting.
 *
 * Designed for extensibility: additional setting groups can be added as new
 * properties without breaking existing consumers.
 */
@injectable()
export class EditorSettingsService {
    protected settings: EditorSettings = {
        isOpen: true,
        isBottomSidebarCollapsed: false
    };

    getSettings(): EditorSettings {
        return this.settings;
    }

    setSettings(settings: EditorSettings): void {
        this.settings = settings;
    }
}
