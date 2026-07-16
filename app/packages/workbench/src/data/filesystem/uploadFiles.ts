import { VSBuffer } from "@codingame/monaco-vscode-api/vscode/vs/base/common/buffer";
import { Uri } from "vscode";
import type { Ref } from "vue";
import type { MonacoApi } from "@/lib/monacoPlugin";
import type { EditorTab } from "@/data/tab/editorTab";
import { showError, showSuccess } from "@/lib/notifications";

/**
 * Uploads dropped or picked CSV files into a project folder, opening the last
 * successfully created file in a new tab.
 *
 * @param files The files to upload
 * @param targetFolderUri The folder to create the files in
 * @param fileService Monaco's file service, used to create the files
 * @param tabs The current editor tabs
 * @param activeTab The currently active editor tab
 */
export async function uploadCsvFiles(
    files: FileList | File[],
    targetFolderUri: Uri,
    fileService: MonacoApi["fileService"],
    tabs: Ref<EditorTab[]>,
    activeTab: Ref<EditorTab | undefined>
): Promise<void> {
    const fileArray = Array.from(files);
    const csvFiles = fileArray.filter((file) => file.name.toLowerCase().endsWith(".csv"));
    const rejectedCount = fileArray.length - csvFiles.length;

    if (rejectedCount > 0) {
        showError(rejectedCount === 1 ? "1 file was skipped" : `${rejectedCount} files were skipped`, {
            description: "Only .csv files can be uploaded here."
        });
    }

    let lastCreatedUri: Uri | undefined;

    for (const file of csvFiles) {
        const uri = Uri.joinPath(targetFolderUri, file.name);
        try {
            const text = await file.text();
            await fileService.createFile(uri, VSBuffer.fromString(text));
            lastCreatedUri = uri;
        } catch (error) {
            showError(`Failed to upload ${file.name}`, {
                description: error instanceof Error ? error.message : undefined
            });
        }
    }

    if (lastCreatedUri == undefined) {
        return;
    }

    showSuccess(csvFiles.length === 1 ? `Uploaded ${csvFiles[0]?.name}` : `Uploaded ${csvFiles.length} files`);

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
