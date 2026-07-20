<template>
    <ProjectsList
        v-if="showProjectsList || project == undefined"
        :projects="projects"
        @create-project="handleCreateProject"
        @close="handleCloseToDetails"
    />
    <ProjectDetails
        v-else
        :users="projectUsers"
        @update-name="handleUpdateProjectName"
        @users-updated="handleUsersUpdated"
        @delete-project="handleDeleteProject"
        @open-projects="handleOpenProjects"
    />
</template>

<script setup lang="ts">
import { ref, inject, onActivated, watch } from "vue";
import { workbenchStateKey } from "@/components/workbench/util";
import type { Project } from "@/data/project/project";
import type { ProjectUserInfo } from "@/data/api/backendApi";
import ProjectsList from "./ProjectsList.vue";
import ProjectDetails from "./ProjectDetails.vue";
import { showApiError } from "@/lib/notifications";

const { backendApi, project, activeSidebar } = inject(workbenchStateKey)!;

const projects = ref<Project[]>([]);
const showProjectsList = ref(false);

const projectUsers = ref<ProjectUserInfo[]>([]);

async function loadProjects() {
    const result = await backendApi.projects.getAll();
    if (result.success) {
        projects.value = result.value;
    } else {
        showApiError("load projects", result.error.message);
    }
}

async function loadProjectDetails() {
    if (!project.value) {
        return;
    }

    const usersResult = await backendApi.projects.getUsers(project.value.id);

    if (usersResult.success) {
        projectUsers.value = usersResult.value.sort((a, b) => {
            return a.username.localeCompare(b.username);
        });
    } else {
        showApiError("load project details", usersResult.error.message);
    }
}

function handleOpenProjects() {
    showProjectsList.value = true;
}

function handleCloseToDetails() {
    if (project.value) {
        showProjectsList.value = false;
    }
}

async function handleCreateProject(name: string) {
    const result = await backendApi.projects.create(name);
    if (result.success) {
        const created = result.value;
        project.value = created;
        await loadProjects();
    } else {
        showApiError("create project", result.error.message);
    }
}

async function handleUpdateProjectName(name: string) {
    if (!project.value) {
        return;
    }

    const result = await backendApi.projects.update(project.value.id, { name });
    if (result.success) {
        const updated = result.value;
        await loadProjects();
        if (project.value?.id === updated.id) {
            project.value.name = updated.name
        }
    } else {
        showApiError("update project name", result.error.message);
    }
}

function handleUsersUpdated() {
    loadProjectDetails();
}

async function handleDeleteProject() {
    if (project.value == undefined) {
        return;
    }

    const result = await backendApi.projects.delete(project.value.id);
    if (result.success) {
        await loadProjects();
        project.value = undefined;
    } else {
        showApiError("delete project", result.error.message);
    }
}

onActivated(async () => {
    await loadProjects();
    if (project.value) {
        await loadProjectDetails();
    }
});

watch(project, async (newProject) => {
    if (newProject != undefined) {
        activeSidebar.value = "files";
        await loadProjectDetails();
    } else {
        activeSidebar.value = "projects";
    }
    showProjectsList.value = false;
});
</script>
