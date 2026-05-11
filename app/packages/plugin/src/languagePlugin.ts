import type { IconNode } from "lucide-vue-next";
import type { languages } from "monaco-editor";

/**
 * Represents a serialized regular expression.
 */
export interface SerializedRegex {
    __regex: true;
    source: string;
    flags: string;
}

/**
 * Utility type that deeply replaces RegExp objects with SerializedRegex objects.
 */
export type DeepSerializeRegex<T> = T extends RegExp
    ? SerializedRegex
    : T extends Array<infer U>
      ? Array<DeepSerializeRegex<U>>
      : T extends object
        ? { [K in keyof T]: DeepSerializeRegex<T[K]> }
        : T;

/**
 * Plugin configuration for a language.
 */
export interface LanguagePlugin {
    /**
     * Unique identifier for the language
     */
    id: string;
    /**
     * The name of the language, displayed in the UI
     */
    name: string;
    /**
     * The file extension associated with the language (including the dot)
     */
    extension?: string;
    /**
     * When true, an action dialog will be triggered when a new file is created
     */
    newFileAction?: boolean;
    /**
     * Server plugin for the language
     */
    serverPlugin: LanguageServerPlugin;
    /**
     * Graphical editor plugin for the language, if undefined no graphical editor will be provided
     */
    graphicalEditorPlugin: LanguageGraphicalEditorPlugin | undefined;
    /**
     * Textual editor plugin for the language, if undefined no textual editor will be provided
     */
    textualEditorPlugin: LanguageTextualEditorPlugin | undefined;
    /**
     * Icon representing the language
     */
    icon: IconNode;
    /**
     * Indicates whether this language was generated automatically
     */
    isGenerated: boolean;
}

/**
 * Plugin configuration for a graphical editor.
 * Provides the container configuration for GLSP/Sprotty diagrams.
 */
export interface LanguageGraphicalEditorPlugin {
    /**
     * Import which resolves to the container configuration for the GLSP/Sprotty container.
     */
    import: string;

    /**
     * URL to the CSS styles for this editor.
     * This should be a URL that can be loaded by the browser.
     */
    stylesUrl: string;

    /**
     * CSS class name to apply to the editor container for styling purposes.
     */
    stylesCls: string;
}

/**
 * Plugin configuration for a textual editor.
 * Provides Monaco editor configuration for syntax highlighting and language features.
 */
export interface LanguageTextualEditorPlugin {
    /**
     * Configuration for the language in the Monaco editor.
     * Includes settings for brackets, comments, auto-closing pairs, etc.
     */
    languageConfiguration: languages.LanguageConfiguration;
    /**
     * Monarch tokens provider for syntax highlighting in the Monaco editor.
     * Defines token patterns and styling rules.
     */
    monarchTokensProvider: DeepSerializeRegex<languages.IMonarchLanguage>;
}

/**
 * Server plugin configuration for a language.
 */
export interface LanguageServerPlugin {
    /**
     * Import which resolves to the server plugin module.
     */
    import: string;
}
