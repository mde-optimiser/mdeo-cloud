import { VSBuffer } from "@codingame/monaco-vscode-api/vscode/vs/base/common/buffer";
import { Uri } from "vscode";
import type { Ref } from "vue";
import type { MonacoApi } from "@/lib/monacoPlugin";
import type { EditorTab } from "@/data/tab/editorTab";
import { showError, showSuccess } from "@/lib/notifications";
import { getFileExtension } from "@/data/filesystem/util";
import type { ResolvedWorkbenchLanguagePlugin } from "@/data/plugin/plugin";

/**
 * Uploads dropped or picked files into a project folder, opening the last
 * successfully created file in a new tab.
 *
 * A file is only accepted if its extension belongs to a registered language
 * plugin, the same rule that decides what a user can create from the "New
 * File" menu.
 *
 * @param files The files to upload
 * @param targetFolderUri The folder to create the files in
 * @param fileService Monaco's file service, used to create the files
 * @param tabs The current editor tabs
 * @param activeTab The currently active editor tab
 * @param languagePluginByExtension Registered language plugins, keyed by file extension
 */
export async function uploadFiles(
    files: FileList | File[],
    targetFolderUri: Uri,
    fileService: MonacoApi["fileService"],
    tabs: Ref<EditorTab[]>,
    activeTab: Ref<EditorTab | undefined>,
    languagePluginByExtension: Ref<Map<string, ResolvedWorkbenchLanguagePlugin>>
): Promise<void> {
    const fileArray = Array.from(files);
    const supportedFiles = fileArray.filter((file) => languagePluginByExtension.value.has(getFileExtension(file.name)));
    const rejectedCount = fileArray.length - supportedFiles.length;

    if (rejectedCount > 0) {
        showError(rejectedCount === 1 ? "1 file was skipped" : `${rejectedCount} files were skipped`, {
            description: "Only files of a supported type can be uploaded here."
        });
    }

    let lastCreatedUri: Uri | undefined;
    const uploaded: string[] = [];

    for (const file of supportedFiles) {
        const uri = Uri.joinPath(targetFolderUri, file.name);
        try {
            const text = await file.text();
            await fileService.createFile(uri, VSBuffer.fromString(text));
            lastCreatedUri = uri;
            uploaded.push(file.name);
        } catch (error) {
            showError(`Failed to upload ${file.name}`, {
                description: error instanceof Error ? error.message : undefined
            });
        }
    }

    if (lastCreatedUri == undefined) {
        return;
    }

    showSuccess(uploaded.length === 1 ? `Uploaded ${uploaded[0]}` : `Uploaded ${uploaded.length} files`);

    const existingTab = tabs.value.find((tab) => tab.fileUri.toString() === lastCreatedUri!.toString());
    if (existingTab) {
        activeTab.value = existingTab;
        existingTab.temporary = false;
    } else {
        const newTab: EditorTab = { fileUri: lastCreatedUri, temporary: false };
        tabs.value.push(newTab);
        activeTab.value = newTab;
    }
}
