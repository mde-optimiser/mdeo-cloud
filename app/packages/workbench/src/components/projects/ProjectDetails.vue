<template>
    <div class="flex flex-col h-full">
        <SidebarPanelHeader label="Project">
            <template #actions>
                <Tooltip>
                    <TooltipTrigger asChild>
                        <Button variant="secondary" size="sm" @click="handleOpenProjects">
                            Open <FolderOpen class="size-4" />
                        </Button>
                    </TooltipTrigger>
                    <TooltipContent side="right">Open Projects</TooltipContent>
                </Tooltip>
            </template>
        </SidebarPanelHeader>

        <div class="flex-1 flex flex-col min-h-0">
            <div class="px-4 pb-3 space-y-3 relative">
                <div class="flex items-center gap-2">
                    <div v-if="isEditingName" class="flex-1 -m-1">
                        <Input
                            v-model="editedName"
                            ref="projectNameInput"
                            @blur="handleSaveName"
                            @keydown.enter="handleSaveName"
                        />
                    </div>
                    <span v-else class="text-base font-semibold flex-1 ml-px">{{ project!.name }}</span>
                    <div v-if="!isEditingName" class="absolute right-4 top-0">
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Button variant="ghost" size="icon" class="h-8 w-8" @click="handleEditName">
                                    <Pencil class="size-4" />
                                </Button>
                            </TooltipTrigger>
                            <TooltipContent side="right">Edit Name</TooltipContent>
                        </Tooltip>
                    </div>
                </div>
            </div>

            <div class="flex-1 flex flex-col min-h-0 overflow-y-auto">
                <div class="flex flex-col min-h-0 pb-2">
                    <Separator />
                    <SidebarPanelHeader label="Plugins">
                        <template #actions>
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <Button
                                        variant="ghost"
                                        size="icon"
                                        class="h-8 w-8"
                                        @click="openManagePluginsDialog"
                                    >
                                        <Settings2 class="size-4" />
                                    </Button>
                                </TooltipTrigger>
                                <TooltipContent side="right">Manage Plugins</TooltipContent>
                            </Tooltip>
                        </template>
                    </SidebarPanelHeader>
                    <ScrollArea class="flex-1 min-h-0 px-2">
                        <Tree
                            v-if="sortedPlugins.length > 0"
                            class="w-full"
                            :active-element="null"
                            :expanded-items="new Set()"
                        >
                            <ContextMenu v-for="plugin in sortedPlugins" :key="plugin.id">
                                <ContextMenuTrigger as-child>
                                    <TreeItem
                                        :data="plugin"
                                        :is-folder="false"
                                        :has-children="false"
                                        :mode="'non-selectable'"
                                        @click="handlePluginClick(plugin as WorkbenchPlugin)"
                                    >
                                        <template #content>
                                            <Icon :iconNode="plugin.icon" name="PluginIcon" class="size-4 mr-2" />
                                            <span class="truncate">{{ plugin.name }}</span>
                                        </template>
                                    </TreeItem>
                                </ContextMenuTrigger>
                                <ContextMenuContent>
                                    <ContextMenuItem
                                        :disabled="isPluginRemovalInProgress(plugin.id)"
                                        @click="handleRemovePlugin(plugin as WorkbenchPlugin)"
                                    >
                                        Remove from Project
                                    </ContextMenuItem>
                                </ContextMenuContent>
                            </ContextMenu>
                        </Tree>
                        <div v-else class="text-sm text-muted-foreground p-2">No plugins added</div>
                    </ScrollArea>
                </div>

                <div class="flex flex-col min-h-0 pb-2">
                    <Separator />
                    <SidebarPanelHeader label="Users">
                        <template #actions>
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <Button variant="ghost" size="icon" class="h-8 w-8" @click="openManageUsersDialog">
                                        <Settings2 class="size-4" />
                                    </Button>
                                </TooltipTrigger>
                                <TooltipContent side="right">Manage Users</TooltipContent>
                            </Tooltip>
                        </template>
                    </SidebarPanelHeader>
                    <ScrollArea class="flex-1 min-h-0 px-2">
                        <Tree v-if="users.length > 0" class="w-full" :active-element="null" :expanded-items="new Set()">
                            <ContextMenu v-for="user in users" :key="user.id">
                                <ContextMenuTrigger as-child>
                                    <TreeItem
                                        :data="user"
                                        :is-folder="false"
                                        :has-children="false"
                                        :mode="'non-selectable'"
                                        @click="handleUserClick(user)"
                                    >
                                        <template #content>
                                            <UserIcon class="size-4 mr-2" />
                                            <span class="truncate">{{ user.username }}</span>
                                            <div class="ml-auto flex items-center gap-1 shrink-0">
                                                <span
                                                    v-if="user.isAdmin"
                                                    class="text-xs px-2 py-0.5 rounded-full bg-secondary text-secondary-foreground font-medium"
                                                    >Admin</span
                                                >
                                                <span
                                                    v-if="!user.isAdmin && user.canExecute"
                                                    class="text-xs px-2 py-0.5 rounded-full bg-secondary text-secondary-foreground font-medium"
                                                    >Execute</span
                                                >
                                                <span
                                                    v-if="!user.isAdmin && user.canWrite"
                                                    class="text-xs px-2 py-0.5 rounded-full bg-secondary text-secondary-foreground font-medium"
                                                    >Write</span
                                                >
                                            </div>
                                        </template>
                                    </TreeItem>
                                </ContextMenuTrigger>
                                <ContextMenuContent>
                                    <ContextMenuItem
                                        :disabled="isUserRemovalInProgress(user.id) || isLastProjectAdmin(user.id)"
                                        @click="handleRemoveUser(user)"
                                    >
                                        Remove from Project
                                    </ContextMenuItem>
                                </ContextMenuContent>
                            </ContextMenu>
                        </Tree>
                        <div v-else class="text-sm text-muted-foreground p-2">No users</div>
                    </ScrollArea>
                </div>

                <div>
                    <Separator />
                    <SidebarPanelHeader label="Git" />
                    <div class="px-4 py-2 space-y-3">
                        <div class="space-y-1.5">
                            <p class="text-[11px] font-medium text-muted-foreground">HTTPS</p>
                            <div class="flex items-center gap-2">
                                <Input
                                    :model-value="gitCloneUrl"
                                    readonly
                                    class="flex-1 text-xs font-mono"
                                    @focus="($event.target as HTMLInputElement).select()"
                                />
                                <Tooltip>
                                    <TooltipTrigger asChild>
                                        <Button
                                            variant="ghost"
                                            size="icon"
                                            class="h-8 w-8 shrink-0"
                                            :aria-label="copiedUrl === 'https' ? 'Copied' : 'Copy clone URL'"
                                            @click="handleCopyGitUrl('https')"
                                        >
                                            <Check v-if="copiedUrl === 'https'" class="size-4" />
                                            <Copy v-else class="size-4" />
                                        </Button>
                                    </TooltipTrigger>
                                    <TooltipContent side="right">
                                        {{ copiedUrl === "https" ? "Copied" : "Copy clone URL" }}
                                    </TooltipContent>
                                </Tooltip>
                            </div>
                        </div>

                        <div v-if="sshCloneUrl" class="space-y-1.5">
                            <p class="text-[11px] font-medium text-muted-foreground">SSH</p>
                            <div class="flex items-center gap-2">
                                <Input
                                    :model-value="sshCloneUrl"
                                    readonly
                                    class="flex-1 text-xs font-mono"
                                    @focus="($event.target as HTMLInputElement).select()"
                                />
                                <Tooltip>
                                    <TooltipTrigger asChild>
                                        <Button
                                            variant="ghost"
                                            size="icon"
                                            class="h-8 w-8 shrink-0"
                                            :aria-label="copiedUrl === 'ssh' ? 'Copied' : 'Copy SSH clone URL'"
                                            @click="handleCopyGitUrl('ssh')"
                                        >
                                            <Check v-if="copiedUrl === 'ssh'" class="size-4" />
                                            <Copy v-else class="size-4" />
                                        </Button>
                                    </TooltipTrigger>
                                    <TooltipContent side="right">
                                        {{ copiedUrl === "ssh" ? "Copied" : "Copy SSH clone URL" }}
                                    </TooltipContent>
                                </Tooltip>
                            </div>
                        </div>

                        <!-- Shown the first time someone takes a URL away, because that is the
                             moment the one-time client setup becomes relevant - and never again
                             once dismissed. -->
                        <div
                            v-if="isSetupHintVisible && setupCommands"
                            class="space-y-2 rounded-lg border border-border/70 bg-muted/30 p-3"
                        >
                            <p class="text-xs font-medium text-foreground">Sign in from git without a password</p>
                            <p class="text-xs text-muted-foreground">
                                Point Git Credential Manager at this server once and it will open a browser to sign you
                                in, then save an access token for every later clone and push.
                            </p>
                            <ScrollArea orientation="horizontal" class="rounded-md bg-background/60">
                                <pre
                                    class="p-2 text-[10px] leading-relaxed font-mono text-muted-foreground"
                                ><code>{{ setupCommands }}</code></pre>
                            </ScrollArea>
                            <div class="flex items-center gap-2">
                                <Button variant="ghost" size="sm" class="h-7 text-xs" @click="handleCopySetup">
                                    <Check v-if="isSetupCopied" class="size-3" />
                                    <Copy v-else class="size-3" />
                                    {{ isSetupCopied ? "Copied" : "Copy commands" }}
                                </Button>
                                <Button
                                    variant="ghost"
                                    size="sm"
                                    class="h-7 text-xs text-muted-foreground"
                                    @click="dismissSetupHint"
                                >
                                    Don't show again
                                </Button>
                            </div>
                        </div>

                        <p class="text-xs text-muted-foreground">
                            Clone or push this project with git, using your MDEO Cloud login.
                        </p>
                    </div>
                </div>

                <div>
                    <Separator />
                    <SidebarPanelHeader label="Management" />
                    <div class="px-4 py-2 space-y-2">
                        <Button variant="secondary" class="w-full" @click="handleDownloadProject">
                            <Download class="size-4 mr-2" />
                            Download as ZIP
                        </Button>
                        <Button variant="destructive" class="w-full" @click="openDeleteDialog">
                            <Trash2 class="size-4 mr-2" />
                            Delete Project
                        </Button>
                    </div>
                </div>
            </div>
        </div>

        <ManagePluginsDialog
            v-model:open="isManagePluginsDialogOpen"
            :project-id="project!.id"
            :selected-plugin-id-prop="selectedPluginIdForDialog"
        />

        <ManageUsersDialog
            v-model:open="isManageUsersDialogOpen"
            :project-id="project!.id"
            :selected-user-id-prop="selectedUserIdForDialog"
            @users-updated="handleUsersUpdated"
        />

        <AlertDialog v-model:open="isDeleteDialogOpen">
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>Delete Project</AlertDialogTitle>
                    <AlertDialogDescription>
                        Are you sure you want to delete "{{ project!.name }}"? This action cannot be undone and will
                        permanently delete all files in this project.
                    </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                    <AlertDialogCancel>Cancel</AlertDialogCancel>
                    <AlertDialogAction @click="handleDeleteProject">Delete</AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    </div>
</template>

<script setup lang="ts">
import { ref, inject, useTemplateRef, nextTick, computed, reactive, onMounted } from "vue";
import { Input } from "@/components/ui/input";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import { Button } from "@/components/ui/button";
import ScrollArea from "@/components/ui/scroll-area/ScrollArea.vue";
import { Separator } from "@/components/ui/separator";
import SidebarPanelHeader from "@/components/sidebar/SidebarPanelHeader.vue";
import Tree from "@/components/tree/Tree.vue";
import TreeItem from "@/components/tree/TreeItem.vue";
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { ContextMenu, ContextMenuContent, ContextMenuItem, ContextMenuTrigger } from "@/components/ui/context-menu";
import ManagePluginsDialog from "./ManagePluginsDialog.vue";
import ManageUsersDialog from "./ManageUsersDialog.vue";
import type { ProjectUserInfo } from "@/data/api/backendApi";
import type { GitAccessConfig } from "@/data/api/areas/gitApi";
import { Pencil, Trash2, Download, FolderOpen, User as UserIcon, Settings2, Icon, Copy, Check } from "@lucide/vue";
import { workbenchStateKey } from "@/components/workbench/util";
import type { WorkbenchPlugin } from "@/data/plugin/plugin";
import { showApiError } from "@/lib/notifications";
import { downloadFolderAsZip } from "@/lib/zip";

const props = defineProps<{
    users: ProjectUserInfo[];
}>();

const emit = defineEmits<{
    updateName: [name: string];
    usersUpdated: [];
    deleteProject: [];
    openProjects: [];
}>();

const { backendApi, project, plugins, monacoApi, fileTree } = inject(workbenchStateKey)!;

const isEditingName = ref(false);
const editedName = ref("");
const isManagePluginsDialogOpen = ref(false);
const isManageUsersDialogOpen = ref(false);
const isDeleteDialogOpen = ref(false);
const projectNameInput = useTemplateRef("projectNameInput");
const selectedPluginIdForDialog = ref<string | undefined>(undefined);
const selectedUserIdForDialog = ref<string | undefined>(undefined);
const removingPluginIds = reactive(new Set<string>());
const removingUserIds = reactive(new Set<string>());

const sortedPlugins = computed(() => {
    return Array.from(plugins.value.values()).sort((a, b) => a.name.localeCompare(b.name));
});

// Same origin as the workbench itself: nginx (and the vite dev proxy) forward
// /git/ to the backend alongside /api/, so no separate backend URL is needed.
const gitCloneUrl = computed(() => `${window.location.origin}/git/${project.value!.id}.git`);
const origin = window.location.origin;

// The SSH port is configurable and, in some deployments, not exposed to
// clients at all - so it is asked for rather than assumed, and no SSH URL is
// shown when there would be nothing listening on the other end.
const gitAccess = ref<GitAccessConfig>();
const sshCloneUrl = computed(() => {
    const access = gitAccess.value;
    if (access == undefined || !access.sshEnabled) {
        return undefined;
    }
    return `ssh://git@${access.sshHost ?? window.location.hostname}:${access.sshPort}/${project.value!.id}.git`;
});

// Device specific rather than account state: whether someone has set up their
// credential helper is a property of the machine they are sitting at.
const SETUP_HINT_DISMISSED_KEY = "mdeo.git.setupHintDismissed";
const isSetupHintVisible = ref(false);
const isSetupCopied = ref(false);
const copiedUrl = ref<"https" | "ssh">();

// Paths come from the server rather than being written out here, so the
// commands always name the endpoints this deployment actually serves.
const setupCommands = computed(() => {
    const access = gitAccess.value;
    if (access == undefined) {
        return "";
    }
    return (
        `git config --global "credential.${origin}.oauthAuthorizeEndpoint" ${access.oauthAuthorizePath}\n` +
        `git config --global "credential.${origin}.oauthTokenEndpoint" ${access.oauthTokenPath}`
    );
});

onMounted(async () => {
    const result = await backendApi.git.getAccessConfig();
    if (result.success) {
        gitAccess.value = result.value;
    }
});

async function handleCopyGitUrl(kind: "https" | "ssh") {
    const url = kind === "ssh" ? sshCloneUrl.value : gitCloneUrl.value;
    if (url == undefined || !(await copyText(url))) {
        return;
    }
    copiedUrl.value = kind;
    setTimeout(() => {
        copiedUrl.value = undefined;
    }, 1500);

    if (localStorage.getItem(SETUP_HINT_DISMISSED_KEY) !== "true") {
        isSetupHintVisible.value = true;
    }
}

async function handleCopySetup() {
    if (!(await copyText(setupCommands.value))) {
        return;
    }
    isSetupCopied.value = true;
    setTimeout(() => {
        isSetupCopied.value = false;
    }, 1500);
}

function dismissSetupHint() {
    localStorage.setItem(SETUP_HINT_DISMISSED_KEY, "true");
    isSetupHintVisible.value = false;
}

/**
 * Copies text, reporting failure rather than throwing: the compose
 * deployments serve the workbench over plain HTTP, where navigator.clipboard
 * is undefined.
 *
 * @param text What to put on the clipboard
 * @return Whether it got there
 */
async function copyText(text: string): Promise<boolean> {
    if (navigator.clipboard == undefined) {
        showApiError("copy to clipboard", "Clipboard unavailable over plain HTTP - select the text and copy it.");
        return false;
    }
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch (error) {
        showApiError("copy to clipboard", String(error));
        return false;
    }
}

function handleEditName() {
    editedName.value = project.value!.name;
    isEditingName.value = true;
    nextTick(() => {
        projectNameInput.value?.$el.focus();
    });
}

function handleSaveName() {
    if (editedName.value.trim() && editedName.value !== project.value!.name) {
        emit("updateName", editedName.value.trim());
    }
    isEditingName.value = false;
}

function openManagePluginsDialog() {
    selectedPluginIdForDialog.value = undefined;
    isManagePluginsDialogOpen.value = true;
}

function openManageUsersDialog() {
    selectedUserIdForDialog.value = undefined;
    isManageUsersDialogOpen.value = true;
}

function openDeleteDialog() {
    isDeleteDialogOpen.value = true;
}

function handleUsersUpdated() {
    emit("usersUpdated");
}

function handleDeleteProject() {
    emit("deleteProject");
}

async function handleDownloadProject() {
    if (!project.value) {
        return;
    }
    await downloadFolderAsZip(monacoApi, fileTree, project.value.name);
}

function handleOpenProjects() {
    emit("openProjects");
}

function handlePluginClick(plugin: WorkbenchPlugin) {
    selectedPluginIdForDialog.value = plugin.id;
    isManagePluginsDialogOpen.value = true;
}

function handleUserClick(user: ProjectUserInfo) {
    selectedUserIdForDialog.value = user.id;
    isManageUsersDialogOpen.value = true;
}

function isPluginRemovalInProgress(pluginId: string): boolean {
    return removingPluginIds.has(pluginId);
}

function isUserRemovalInProgress(userId: string): boolean {
    return removingUserIds.has(userId);
}

function isLastProjectAdmin(userId: string): boolean {
    const adminUsers = props.users.filter((user) => user.isAdmin);
    return adminUsers.length === 1 && adminUsers[0]?.id === userId;
}

async function handleRemovePlugin(plugin: WorkbenchPlugin) {
    if (removingPluginIds.has(plugin.id)) {
        return;
    }

    removingPluginIds.add(plugin.id);
    try {
        const result = await backendApi.plugins.removeFromProject(project.value!.id, plugin.id);
        if (result.success) {
            plugins.value.delete(plugin.id);
        } else {
            showApiError("remove plugin from project", result.error.message);
        }
    } finally {
        removingPluginIds.delete(plugin.id);
    }
}

async function handleRemoveUser(user: ProjectUserInfo) {
    if (removingUserIds.has(user.id)) {
        return;
    }

    if (isLastProjectAdmin(user.id)) {
        return;
    }

    removingUserIds.add(user.id);
    try {
        const result = await backendApi.projects.removeUser(project.value!.id, user.id);
        if (result.success) {
            emit("usersUpdated");
        } else {
            showApiError("remove user from project", result.error.message);
        }
    } finally {
        removingUserIds.delete(user.id);
    }
}
</script>
