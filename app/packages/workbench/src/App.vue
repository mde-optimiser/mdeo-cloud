<template>
    <TooltipProvider>
        <Workbench
            v-if="workspaceState != undefined && !isAuthorizingGit"
            :workbenchState="workspaceState"
            :auth-state="authState"
        />
        <div v-else-if="!isCheckingAuth" class="flex h-screen w-screen">
            <div class="flex h-full border-r border-border/60">
                <BaseSidebarRail />
            </div>
            <div class="flex flex-1 items-center justify-center px-4 py-10">
                <AuthorizeGitCard
                    v-if="isAuthorizingGit && authState.isAuthenticated.value"
                    :backend-api="backendApi"
                    :username="authState.user.value?.username ?? ''"
                />
                <LoginCard v-else :auth-state="authState" @success="handleAuthSuccess" />
            </div>
        </div>
    </TooltipProvider>
    <Toaster :theme="theme == 'auto' ? 'system' : theme" />
</template>
<script setup lang="ts">
import { inject, shallowRef, onMounted, ref } from "vue";
import { monacoApiProviderKey } from "./lib/monacoPlugin";
import { BackendApi } from "./data/api/backendApi";
import { WorkbenchState } from "./data/workbenchState";
import { AuthState } from "./data/authState";
import Workbench from "./components/workbench/Workbench.vue";
import LoginCard from "./components/auth/LoginCard.vue";
import AuthorizeGitCard from "./components/auth/AuthorizeGitCard.vue";
import { isGitOAuthAuthorizePath, DEFAULT_GIT_OAUTH_AUTHORIZE_PATH } from "./data/gitOAuthRequest";
import BaseSidebarRail from "./components/sidebar/BaseSidebarRail.vue";
import { useColorMode } from "@vueuse/core";
import { TooltipProvider } from "./components/ui/tooltip";
import { Toaster } from "./components/ui/sonner";
import "vue-sonner/style.css";
import { useEditorSettingsPersistence } from "./data/editorSettingsPersistence";

useEditorSettingsPersistence();

const monaco = inject(monacoApiProviderKey)!;
const backendApi = new BackendApi();
const workspaceState = shallowRef<WorkbenchState>();

const authState = new AuthState(backendApi, () => {
    workspaceState.value = undefined;
});

const isCheckingAuth = ref(true);

// A git credential helper opens this path directly. Signing in is the login
// card below, exactly as anywhere else in the product - the authorization
// screen only ever asks the question it owns. The path is the server's to
// decide, so it is resolved before anything renders.
const isAuthorizingGit = ref(isGitOAuthAuthorizePath(DEFAULT_GIT_OAUTH_AUTHORIZE_PATH));

const theme = useColorMode();

onMounted(async () => {
    const [, gitConfig] = await Promise.all([authState.checkAuthentication(), backendApi.git.getAccessConfig()]);
    if (gitConfig.success) {
        isAuthorizingGit.value = isGitOAuthAuthorizePath(gitConfig.value.oauthAuthorizePath);
    }
    if (authState.isAuthenticated.value && !isAuthorizingGit.value) {
        await initializeWorkbench();
    }
    isCheckingAuth.value = false;
});

async function handleAuthSuccess() {
    if (isAuthorizingGit.value) {
        // Signing in from the authorization screen leaves the user on it,
        // now able to answer, rather than dropping them into the workbench.
        return;
    }
    await initializeWorkbench();
}

async function initializeWorkbench() {
    const monacoApi = await monaco;
    const state = new WorkbenchState(monacoApi, backendApi);

    await syncProjectFromPath(state);
    workspaceState.value = state;
}

async function syncProjectFromPath(state: WorkbenchState) {
    const path = window.location.pathname;
    if (path === "/") {
        return;
    }
    const projectId = path.startsWith("/") ? path.slice(1) : path;
    const result = await backendApi.projects.getAll();
    if (!result.success) {
        return;
    }
    const project = result.value.find((p) => p.id === projectId);
    if (project) {
        state.project.value = project;
        state.activeSidebar.value = "files";
    }
}
</script>
