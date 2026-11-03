package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.canCreateProject
import com.mdeo.backend.plugins.getUserSession
import com.mdeo.backend.plugins.isAdmin
import com.mdeo.backend.service.AddProjectUserResult
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.backend.service.RemoveProjectUserResult
import com.mdeo.backend.service.UpdateProjectUserPermissionsResult
import com.mdeo.common.model.AddOwnerRequest
import com.mdeo.common.model.AddProjectUserRequest
import com.mdeo.common.model.CreateProjectRequest
import com.mdeo.common.model.UpdateProjectRequest
import com.mdeo.common.model.UpdateProjectUserPermissionsRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

/**
 * Configures project management routes.
 *
 * @param projectService Service for project operations
 */
fun Route.projectRoutes(projectService: ProjectService) {
    route("/api/projects") {
        get {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@get
            }

            val userId = parseUserId(session.userId) ?: run {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@get
            }

            val projects = projectService.getProjectsForUser(userId, call.isAdmin())
            call.respond(projects)
        }

        post {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@post
            }

            if (!call.canCreateProject()) {
                call.respondError(HttpStatusCode.Forbidden, "Create project permission required")
                return@post
            }

            val userId = parseUserId(session.userId) ?: run {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@post
            }

            val request = call.receive<CreateProjectRequest>()
            val project = projectService.createProject(request.name, userId)
            call.respond(HttpStatusCode.Created, project)
        }

        route("/{projectId}") {
            get {
                val session = call.getUserSession()
                if (session == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                    return@get
                }

                val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@get
                }

                val userId = parseUserId(session.userId) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@get
                }

                if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                    call.respondError(HttpStatusCode.Forbidden, "Access denied")
                    return@get
                }

                val project = projectService.getProject(projectId)
                if (project == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Project not found")
                    return@get
                }

                call.respond(project)
            }

            put {
                val session = call.getUserSession()
                if (session == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                    return@put
                }

                val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@put
                }

                val userId = parseUserId(session.userId) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@put
                }

                if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.ADMIN)) {
                    call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                    return@put
                }

                val request = call.receive<UpdateProjectRequest>()
                val updated = projectService.updateProject(projectId, request)
                if (!updated) {
                    call.respondError(HttpStatusCode.BadRequest, "Project not found")
                    return@put
                }

                val updatedProject = projectService.getProject(projectId)
                if (updatedProject == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Project not found")
                    return@put
                }

                call.respond(updatedProject)
            }

            delete {
                val session = call.getUserSession()
                if (session == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                    return@delete
                }

                val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@delete
                }

                val userId = parseUserId(session.userId) ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@delete
                }

                if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.ADMIN)) {
                    call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                    return@delete
                }

                val deleted = projectService.deleteProject(projectId)
                if (!deleted) {
                    call.respondError(HttpStatusCode.BadRequest, "Project not found")
                    return@delete
                }

                call.respond(Unit)
            }

            route("/users") {
                get {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@get
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@get
                    }

                    val userId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@get
                    }

                    if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                        call.respondError(HttpStatusCode.Forbidden, "Access denied")
                        return@get
                    }

                    val users = projectService.getProjectUsers(projectId)
                    call.respond(users)
                }

                post {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@post
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@post
                    }

                    val currentUserId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@post
                    }

                    if (!projectService.hasProjectPermission(projectId, currentUserId, call.isAdmin(), ProjectPermission.ADMIN)) {
                        call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                        return@post
                    }

                    val request = call.receive<AddProjectUserRequest>()
                    val userId = parseUserId(request.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@post
                    }

                    when (
                        projectService.addProjectUser(
                            projectId = projectId,
                            userId = userId,
                            isAdmin = request.isAdmin,
                            canExecute = request.canExecute,
                            canWrite = request.canWrite
                        )
                    ) {
                        AddProjectUserResult.SUCCESS -> call.respond(Unit)
                        AddProjectUserResult.PROJECT_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "Project not found")
                        AddProjectUserResult.USER_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "User not found")
                        AddProjectUserResult.ALREADY_MEMBER ->
                            call.respondError(HttpStatusCode.BadRequest, "User is already in the project")
                    }
                }

                put("/{userId}/permissions") {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@put
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@put
                    }

                    val currentUserId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@put
                    }

                    if (!projectService.hasProjectPermission(projectId, currentUserId, call.isAdmin(), ProjectPermission.ADMIN)) {
                        call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                        return@put
                    }

                    val userId = parseUserId(call.parameters["userId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@put
                    }

                    val request = call.receive<UpdateProjectUserPermissionsRequest>()
                    when (
                        projectService.updateProjectUserPermissions(
                            projectId = projectId,
                            userId = userId,
                            isAdmin = request.isAdmin,
                            canExecute = request.canExecute,
                            canWrite = request.canWrite
                        )
                    ) {
                        UpdateProjectUserPermissionsResult.SUCCESS -> call.respond(Unit)
                        UpdateProjectUserPermissionsResult.PROJECT_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "Project not found")
                        UpdateProjectUserPermissionsResult.USER_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "User not found")
                        UpdateProjectUserPermissionsResult.NOT_MEMBER ->
                            call.respondError(HttpStatusCode.BadRequest, "User is not part of this project")
                        UpdateProjectUserPermissionsResult.LAST_PROJECT_ADMIN ->
                            call.respondError(HttpStatusCode.BadRequest, "Cannot remove the last project admin")
                    }
                }

                delete("/{userId}") {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@delete
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@delete
                    }

                    val currentUserId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@delete
                    }

                    if (!projectService.hasProjectPermission(projectId, currentUserId, call.isAdmin(), ProjectPermission.ADMIN)) {
                        call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                        return@delete
                    }

                    val userId = parseUserId(call.parameters["userId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@delete
                    }

                    when (projectService.removeProjectUser(projectId, userId)) {
                        RemoveProjectUserResult.SUCCESS -> call.respond(Unit)
                        RemoveProjectUserResult.PROJECT_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "Project not found")
                        RemoveProjectUserResult.NOT_MEMBER ->
                            call.respondError(HttpStatusCode.BadRequest, "User is not part of this project")
                        RemoveProjectUserResult.LAST_PROJECT_ADMIN ->
                            call.respondError(HttpStatusCode.BadRequest, "Cannot remove the last project admin")
                    }
                }
            }

            route("/owners") {
                get {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@get
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@get
                    }

                    val userId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@get
                    }

                    if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                        call.respondError(HttpStatusCode.Forbidden, "Access denied")
                        return@get
                    }

                    call.respond(projectService.getProjectUsers(projectId))
                }

                post {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@post
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@post
                    }

                    val currentUserId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@post
                    }

                    if (!projectService.hasProjectPermission(projectId, currentUserId, call.isAdmin(), ProjectPermission.ADMIN)) {
                        call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                        return@post
                    }

                    val request = call.receive<AddOwnerRequest>()
                    val userId = parseUserId(request.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@post
                    }

                    when (
                        projectService.addProjectUser(
                            projectId = projectId,
                            userId = userId,
                            isAdmin = true,
                            canExecute = true,
                            canWrite = true
                        )
                    ) {
                        AddProjectUserResult.SUCCESS -> call.respond(Unit)
                        AddProjectUserResult.PROJECT_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "Project not found")
                        AddProjectUserResult.USER_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "User not found")
                        AddProjectUserResult.ALREADY_MEMBER ->
                            call.respondError(HttpStatusCode.BadRequest, "User is already in the project")
                    }
                }

                delete("/{userId}") {
                    val session = call.getUserSession()
                    if (session == null) {
                        call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                        return@delete
                    }

                    val projectId = parseProjectId(call.parameters["projectId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@delete
                    }

                    val currentUserId = parseUserId(session.userId) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@delete
                    }

                    if (!projectService.hasProjectPermission(projectId, currentUserId, call.isAdmin(), ProjectPermission.ADMIN)) {
                        call.respondError(HttpStatusCode.Forbidden, "Project admin access required")
                        return@delete
                    }

                    val userId = parseUserId(call.parameters["userId"]) ?: run {
                        call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                        return@delete
                    }

                    when (projectService.removeProjectUser(projectId, userId)) {
                        RemoveProjectUserResult.SUCCESS -> call.respond(Unit)
                        RemoveProjectUserResult.PROJECT_NOT_FOUND ->
                            call.respondError(HttpStatusCode.BadRequest, "Project not found")
                        RemoveProjectUserResult.NOT_MEMBER ->
                            call.respondError(HttpStatusCode.BadRequest, "User is not part of this project")
                        RemoveProjectUserResult.LAST_PROJECT_ADMIN ->
                            call.respondError(HttpStatusCode.BadRequest, "Cannot remove the last project admin")
                    }
                }
            }
        }
    }
}

private fun parseProjectId(rawProjectId: String?): UUID? {
    if (rawProjectId.isNullOrBlank()) {
        return null
    }
    return try {
        UUID.fromString(rawProjectId)
    } catch (exception: Exception) {
        null
    }
}

private fun parseUserId(rawUserId: String?): UUID? {
    if (rawUserId.isNullOrBlank()) {
        return null
    }
    return try {
        UUID.fromString(rawUserId)
    } catch (exception: Exception) {
        null
    }
}
