import type { MonacoApi } from "@/lib/monacoPlugin";
import { reactive, watch, markRaw } from "vue";
import type { Folder, FileSystemNode, File } from "./file";
import type { Project } from "../project/project";
import type { WorkbenchState } from "../workbenchState";
import { FileType } from "@codingame/monaco-vscode-files-service-override";
import type { FileOperationEvent } from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import {
    FileOperation,
    type IFileStat,
    type IFileStatWithMetadata
} from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import { Uri } from "vscode";
import { getFileExtension } from "./util";

/**
 * Creates a reactive folder tree for a project that automatically updates
 * when file system operations occur.
 *
 * @param monacoApi Monaco API instance providing access to file service
 * @param workbenchState The workbench state manager
 * @returns A reactive folder representing the project's root directory
 */
export function useFileTree(monacoApi: MonacoApi, workbenchState: WorkbenchState): Folder {
    const project = workbenchState.project;
    const root = reactive<Folder>({
        id: "/",
        name: "",
        type: FileType.Directory,
        uri: markRaw(Uri.file("/")),
        children: [],
        parent: null
    });

    // Incremented on every project change, so that listings still arriving for the previous project
    // are dropped instead of being merged into the new tree.
    let generation = 0;

    const loadTree = async (currentProject: Project) => {
        const loadGeneration = ++generation;
        root.name = currentProject.name;
        root.uri = markRaw(Uri.file(`/${currentProject.id}/files`));
        root.children = [];
        await loadSubtree(monacoApi, root, () => loadGeneration === generation);
    };

    watch(
        project,
        async (newProject) => {
            if (newProject != undefined) {
                await loadTree(newProject);
            }
        },
        { immediate: true }
    );

    monacoApi.fileService.onDidRunOperation((event) => {
        handleFileOperation(monacoApi, root, event, project.value, workbenchState);
    });

    return root;
}

/**
 * Maximum number of folder listings requested from the file system at the same time.
 */
const MAX_CONCURRENT_LISTINGS = 8;

/**
 * Loads the whole subtree below a folder breadth-first, adding each folder's children to the tree
 * as soon as its listing arrives.
 *
 * Folders are listed in the order they were found, at most {@link MAX_CONCURRENT_LISTINGS} at a
 * time, so shallow folders fill in before deep ones and one slow listing does not hold up the rest.
 * A folder whose listing fails stays empty; the others are still loaded.
 *
 * @param monacoApi Monaco API instance providing access to file service
 * @param folder The folder whose subtree to load
 * @param isCurrent Returns false once the result is no longer wanted; pending listings are then dropped
 * @returns Promise that resolves once the subtree is loaded or abandoned
 */
function loadSubtree(monacoApi: MonacoApi, folder: Folder, isCurrent: () => boolean = () => true): Promise<void> {
    const queue: Folder[] = [folder];
    let active = 0;

    return new Promise((resolve) => {
        const next = () => {
            while (active < MAX_CONCURRENT_LISTINGS && queue.length > 0 && isCurrent()) {
                const current = queue.shift();
                if (current == undefined) {
                    break;
                }

                active++;
                monacoApi.fileService
                    .resolve(current.uri)
                    .then((stat) => {
                        if (isCurrent()) {
                            queue.push(...addChildren(current, stat));
                        }
                    })
                    .catch((error) => {
                        // eslint-disable-next-line no-console
                        console.error(`Failed to list ${current.uri.path}`, error);
                    })
                    .finally(() => {
                        active--;
                        next();
                    });
            }

            if (active === 0) {
                resolve();
            }
        };

        next();
    });
}

/**
 * Adds the entries of a folder listing to the folder's children.
 *
 * Entries already in the tree (e.g. added by a file operation while the listing was pending) are
 * kept as they are, so that no node is replaced.
 *
 * @param folder The folder the listing belongs to
 * @param stat The resolved folder, including its children
 * @returns All subfolders of the folder, to be loaded next
 */
function addChildren(folder: Folder, stat: IFileStat): Folder[] {
    const existing = new Map(folder.children.map((child) => [child.name, child]));
    const subfolders: Folder[] = [];

    for (const entry of stat.children ?? []) {
        const known = existing.get(entry.name);
        if (known != undefined) {
            if (known.type === FileType.Directory) {
                subfolders.push(known);
            }
            continue;
        }

        const childUri = markRaw(entry.resource);

        if (entry.isDirectory) {
            const child = reactive<Folder>({
                id: childUri.path,
                name: entry.name,
                type: FileType.Directory,
                uri: childUri,
                children: [],
                parent: folder
            });
            folder.children.push(child);
            subfolders.push(child);
        } else {
            folder.children.push(
                reactive<File>({
                    id: childUri.path,
                    name: entry.name,
                    type: FileType.File,
                    uri: childUri,
                    parent: folder,
                    extension: getFileExtension(entry.name)
                })
            );
        }
    }

    return subfolders;
}

/**
 * Handles file system operation events and updates the tree accordingly.
 *
 * @param monacoApi Monaco API instance providing access to file service
 * @param root Root folder of the file tree
 * @param event File operation event to handle
 * @param currentProject Currently active project
 * @param workbenchState The workbench state manager
 */
async function handleFileOperation(
    monacoApi: MonacoApi,
    root: Folder,
    event: FileOperationEvent,
    currentProject: Project | undefined,
    workbenchState: WorkbenchState
): Promise<void> {
    if (currentProject == undefined) {
        return;
    }

    const projectPrefix = `/${currentProject.id}/files`;

    if (event.operation === FileOperation.CREATE && event.target != undefined) {
        handleCreate(monacoApi, root, event.target, projectPrefix);
    } else if (event.operation === FileOperation.DELETE) {
        handleDelete(root, event.resource, projectPrefix, workbenchState);
    } else if (event.operation === FileOperation.MOVE && event.target != undefined) {
        handleMove(root, event.resource, event.target.resource, projectPrefix);
    } else if (event.operation === FileOperation.COPY && event.target != undefined) {
        handleCreate(monacoApi, root, event.target, projectPrefix);
    }
}

/**
 * Handles file/folder creation by adding the node to the tree.
 *
 * @param monacoApi Monaco API instance providing access to file service
 * @param root Root folder of the file tree
 * @param target The created file/folder
 * @param projectPrefix Uri prefix for the current project
 */
async function handleCreate(
    monacoApi: MonacoApi,
    root: Folder,
    target: IFileStatWithMetadata,
    projectPrefix: string
): Promise<void> {
    const resource = target.resource;
    const resourcePath = resource.path;
    if (!resourcePath.startsWith(projectPrefix)) {
        return;
    }

    const parent = ensureParentFolder(root, resource);
    if (parent == undefined) {
        return;
    }

    const name = getResourceName(resource);

    if (target.isDirectory) {
        const folder = ensureFolder(parent, name);
        if (folder == undefined) {
            return;
        }

        await loadSubtree(monacoApi, folder);
    } else {
        if (parent.children.some((child) => child.name === name)) {
            return;
        }

        const childUri = markRaw(resource);
        parent.children.push(
            reactive<File>({
                id: childUri.path,
                name,
                type: FileType.File,
                uri: childUri,
                parent,
                extension: getFileExtension(name)
            })
        );
    }
}

/**
 * Returns the child folder with the given name, adding it to the tree if it does not exist yet.
 *
 * @param parent Folder to look in
 * @param name Name of the child folder
 * @returns The existing or newly added folder, or null if a file of that name already exists
 */
function ensureFolder(parent: Folder, name: string): Folder | null {
    const existing = parent.children.find((child) => child.name === name);
    if (existing != undefined) {
        return existing.type === FileType.Directory ? existing : null;
    }

    const folderUri = markRaw(Uri.file(`${parent.uri.path}/${name}`));
    const folder = reactive<Folder>({
        id: folderUri.path,
        name,
        type: FileType.Directory,
        uri: folderUri,
        children: [],
        parent
    });
    parent.children.push(folder);

    return folder;
}

/**
 * Finds the parent folder of a created resource, adding any missing folders along the way.
 *
 * Writing a file to a nested path implicitly creates its parent folders, but the file service only
 * reports the created file itself, so those folders would otherwise never show up in the tree.
 *
 * @param root Root folder of the file tree
 * @param resource Uri of the created resource
 * @returns The parent folder, or null if it cannot be represented in the tree
 */
function ensureParentFolder(root: Folder, resource: Uri): Folder | null {
    const path = resource.path;
    const segments = path.split("/").filter((s) => s.length > 0);

    if (segments.length <= 2) {
        return null;
    }

    let current: Folder = root;

    for (let i = 2; i < segments.length - 1; i++) {
        const name = segments[i];
        if (name == undefined) {
            return null;
        }

        const child = ensureFolder(current, name);
        if (child == null) {
            return null;
        }

        current = child;
    }

    return current;
}

/**
 * Handles file/folder deletion by removing the node from the tree.
 *
 * @param root Root folder of the file tree
 * @param resource Uri of the deleted resource
 * @param projectPrefix Uri prefix for the current project
 * @param workbenchState The workbench state manager
 */
function handleDelete(root: Folder, resource: Uri, projectPrefix: string, workbenchState: WorkbenchState): void {
    const resourcePath = resource.path;
    if (!resourcePath.startsWith(projectPrefix)) {
        return;
    }

    const parent = findParentFolder(root, resource);
    if (parent == null) {
        return;
    }

    const name = getResourceName(resource);
    const index = parent.children.findIndex((child) => child.name === name);
    if (index !== -1) {
        const deletedNode = parent.children[index];

        if (deletedNode) {
            closeTabsForNode(deletedNode, workbenchState);
        }

        parent.children.splice(index, 1);
    }
}

/**
 * Handles file/folder move by updating the node's parent and Uris.
 *
 * @param root Root folder of the file tree
 * @param oldResource Old Uri of the resource
 * @param newResource New Uri of the resource
 * @param projectPrefix Uri prefix for the current project
 */
function handleMove(root: Folder, oldResource: Uri, newResource: Uri, projectPrefix: string): void {
    const oldResourcePath = oldResource.path;
    if (!oldResourcePath.startsWith(projectPrefix)) {
        return;
    }

    const newResourcePath = newResource.path;
    if (!newResourcePath.startsWith(projectPrefix)) {
        return;
    }

    const oldParent = findParentFolder(root, oldResource);
    if (oldParent == undefined) {
        return;
    }

    const oldName = getResourceName(oldResource);
    const nodeIndex = oldParent.children.findIndex((child) => child.name === oldName);
    if (nodeIndex === -1) {
        return;
    }

    const node = oldParent.children[nodeIndex];
    if (node == undefined) {
        return;
    }

    const newParent = findParentFolder(root, newResource);
    if (newParent == undefined) {
        return;
    }

    const newName = getResourceName(newResource);

    oldParent.children.splice(nodeIndex, 1);

    node.name = newName;
    node.uri = markRaw(newResource);
    node.parent = newParent;

    if (node.type === FileType.Directory) {
        updateDescendantUris(node, oldResource, newResource);
    }

    newParent.children.push(node);
}

/**
 * Recursively updates Uris for all descendants of a moved folder.
 *
 * @param folder The folder whose descendants to update
 * @param oldBaseUri The old base Uri
 * @param newBaseUri The new base Uri
 */
function updateDescendantUris(folder: Folder, oldBaseUri: Uri, newBaseUri: Uri): void {
    for (const child of folder.children) {
        const oldPath = child.uri.path;
        const oldBasePath = oldBaseUri.path;
        const newBasePath = newBaseUri.path;

        const newPath = oldPath.replace(oldBasePath, newBasePath);
        child.uri = markRaw(Uri.file(newPath));

        if (child.type === FileType.Directory) {
            updateDescendantUris(child, oldBaseUri, newBaseUri);
        }
    }
}

/**
 * Closes all tabs associated with a file or folder (recursively).
 *
 * @param node The file or folder node
 * @param workbenchState The workbench state manager
 */
function closeTabsForNode(node: FileSystemNode, workbenchState: WorkbenchState): void {
    const tabsToClose: number[] = [];
    const nodePath = node.uri.path;

    workbenchState.tabs.value.forEach((tab, index) => {
        const tabPath = tab.fileUri.path;
        if (tabPath === nodePath || tabPath.startsWith(nodePath + "/")) {
            tabsToClose.push(index);
        }
    });

    for (let i = tabsToClose.length - 1; i >= 0; i--) {
        const tabIndex = tabsToClose[i];
        if (tabIndex === undefined) {
            continue;
        }

        const closedTab = workbenchState.tabs.value[tabIndex];
        if (!closedTab) {
            continue;
        }

        if (workbenchState.activeTab.value === closedTab) {
            const newActiveIndex =
                tabIndex > 0 ? tabIndex - 1 : tabIndex < workbenchState.tabs.value.length - 1 ? tabIndex : -1;
            if (newActiveIndex >= 0) {
                workbenchState.activeTab.value = workbenchState.tabs.value[newActiveIndex];
            } else {
                workbenchState.activeTab.value = undefined;
            }
        }

        workbenchState.tabs.value.splice(tabIndex, 1);
    }
}

/**
 * Finds the parent folder of a given resource in the tree.
 *
 * @param root Root folder of the file tree
 * @param resource Uri of the resource whose parent to find
 * @returns The parent folder, or null if not found
 */
function findParentFolder(root: Folder, resource: Uri): Folder | null {
    const path = resource.path;
    const segments = path.split("/").filter((s) => s.length > 0);

    if (segments.length <= 2) {
        return null;
    }

    let current: FileSystemNode = root;

    for (let i = 2; i < segments.length - 1; i++) {
        if (current.type !== FileType.Directory) {
            return null;
        }

        const child: FileSystemNode | undefined = current.children.find((c) => c.name === segments[i]);
        if (!child) {
            return null;
        }

        current = child;
    }

    return current.type === FileType.Directory ? current : null;
}

/**
 * Extracts the name of a resource from its Uri.
 *
 * @param resource Uri of the resource
 * @returns The name of the resource
 */
function getResourceName(resource: Uri): string {
    const path = resource.path;
    const segments = path.split("/").filter((s) => s.length > 0);
    return segments[segments.length - 1] || "";
}
