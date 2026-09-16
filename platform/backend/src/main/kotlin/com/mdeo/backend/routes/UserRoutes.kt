package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.ProjectService
import com.mdeo.backend.service.UpdateUserPermissionsResult
import com.mdeo.backend.service.UserService
import com.mdeo.common.model.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

/**
 * Backward-compatible request body for admin-only updates.
 */
@Serializable
data class UpdateUserAdminRequest(
    val isAdmin: Boolean
)

/**
 * Configures user management routes.
 *
 * @param userService Service for user operations
 * @param projectService Service for project operations
 */
fun Route.userRoutes(userService: UserService, projectService: ProjectService) {
    route("/api/users") {
        /**
         * Gets all users in the system (admin only).
         *
         * @return ApiResult.Success with list of users, 401 if not authenticated, 403 if not admin
         */
        get {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@get
            }
            
            if (!call.isAdmin()) {
                call.respondError(HttpStatusCode.Forbidden, "Admin access required")
                return@get
            }
            
            val users = userService.getAllUsers()
            call.respond(users)
        }
        
        route("/{userId}") {
            /**
             * Gets all project memberships for a specific user (admin only).
             *
             * @param userId Path parameter for user UUID
             * @return ApiResult.Success with list of projects, 401/403/400 on errors
             */
            get("/projects") {
                val session = call.getUserSession()
                if (session == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                    return@get
                }
                
                if (!call.isAdmin()) {
                    call.respondError(HttpStatusCode.Forbidden, "Admin access required")
                    return@get
                }
                
                val userId = call.parameters["userId"]?.let { 
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (userId == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@get
                }
                
                val projects = projectService.getProjectMembershipsByUserId(userId)
                call.respond(projects)
            }
            
            /**
             * Updates a user's global permissions (admin only).
             *
             * @param userId Path parameter for user UUID
             * @param body UpdateUserPermissionsRequest with permission values
             * @return ApiResult.Success on success, 401/403/400/404 on errors
             */
            put("/permissions") {
                val session = call.getUserSession()
                if (session == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                    return@put
                }
                
                if (!call.isAdmin()) {
                    call.respondError(HttpStatusCode.Forbidden, "Admin access required")
                    return@put
                }
                
                val userId = call.parameters["userId"]?.let { 
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (userId == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@put
                }
                
                val request = call.receive<UpdateUserPermissionsRequest>()

                when (
                    userService.updateUserGlobalPermissions(
                        userId = userId,
                        isAdmin = request.isAdmin,
                        canCreateProject = request.canCreateProject
                    )
                ) {
                    UpdateUserPermissionsResult.SUCCESS -> call.respond(Unit)
                    UpdateUserPermissionsResult.USER_NOT_FOUND ->
                        call.respondError(HttpStatusCode.BadRequest, "User not found")
                    UpdateUserPermissionsResult.LAST_GLOBAL_ADMIN ->
                        call.respondError(HttpStatusCode.BadRequest, "Cannot remove the last global admin")
                }
            }

            /**
             * Backward-compatible endpoint for updating admin status only.
             */
            put("/admin") {
                val session = call.getUserSession()
                if (session == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                    return@put
                }

                if (!call.isAdmin()) {
                    call.respondError(HttpStatusCode.Forbidden, "Admin access required")
                    return@put
                }

                val userId = call.parameters["userId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (userId == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@put
                }

                val request = call.receive<UpdateUserAdminRequest>()
                val canCreateProject = request.isAdmin

                when (
                    userService.updateUserGlobalPermissions(
                        userId = userId,
                        isAdmin = request.isAdmin,
                        canCreateProject = canCreateProject
                    )
                ) {
                    UpdateUserPermissionsResult.SUCCESS -> call.respond(Unit)
                    UpdateUserPermissionsResult.USER_NOT_FOUND ->
                        call.respondError(HttpStatusCode.BadRequest, "User not found")
                    UpdateUserPermissionsResult.LAST_GLOBAL_ADMIN ->
                        call.respondError(HttpStatusCode.BadRequest, "Cannot remove the last global admin")
                }
            }
        }
    }
}
