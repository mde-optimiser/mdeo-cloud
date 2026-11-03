package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.UserService
import com.mdeo.common.model.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

/**
 * Configures admin and user password management routes.
 *
 * @param userService Service for user management operations
 */
fun Route.adminRoutes(userService: UserService) {
    route("/api/admin") {
        /**
         * Changes another user's password (admin only).
         *
         * @param userId Path parameter for user UUID
         * @param body AdminChangePasswordRequest with new password
         * @return ApiResult.Success on success, 403 if not admin, 404 if user not found
         */
        put("/users/{userId}/password") {
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
            
            val request = call.receive<AdminChangePasswordRequest>()
            
            val changed = userService.adminChangePassword(userId, request.newPassword)
            if (!changed) {
                call.respondError(HttpStatusCode.BadRequest, "User not found")
                return@put
            }
            
            call.respond(Unit)
        }
    }
    
    route("/api/auth") {
        /**
         * Changes the current user's password.
         *
         * @param body ChangePasswordRequest with current and new password
         * @return ApiResult.Success on success, 401 if not authenticated, 400 if current password incorrect
         */
        put("/password") {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@put
            }
            
            val request = call.receive<ChangePasswordRequest>()
            
            val userId = try { UUID.fromString(session.userId) } catch (e: Exception) { null }
            if (userId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@put
            }
            
            val changed = userService.changePassword(
                userId,
                request.currentPassword,
                request.newPassword
            )
            
            if (!changed) {
                call.respondError(HttpStatusCode.BadRequest, "Current password is incorrect")
                return@put
            }
            
            call.respond(Unit)
        }
    }
}
