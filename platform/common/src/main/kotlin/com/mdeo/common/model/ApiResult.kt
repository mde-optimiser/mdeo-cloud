package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * Represents the result of an API operation.
 * Mirrors the TypeScript ApiResult type for frontend compatibility.
 */
@Serializable
sealed class ApiResult<out T> {
    /**
     * Represents a successful API operation result.
     *
     * @property value The successfully returned value
     */
    @Serializable
    data class Success<T>(val value: T) : ApiResult<T>()
    
    /**
     * Represents a failed API operation result.
     *
     * @property error Details about the error that occurred
     */
    @Serializable
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

/**
 * Represents an error returned from an API operation.
 *
 * @property code Error code identifying the type of error
 * @property message Human-readable error message
 */
@Serializable
data class ApiError(
    val code: String,
    val message: String
)

/**
 * Error codes matching the TypeScript frontend.
 */
object ErrorCodes {
    const val UNAVAILABLE = "Unavailable"
    const val UNKNOWN = "Unknown"
    
    const val FILE_NOT_FOUND = "FileNotFound"
    const val FILE_EXISTS = "FileExists"
    const val FILE_IS_A_DIRECTORY = "FileIsADirectory"
    const val FILE_NOT_A_DIRECTORY = "FileNotADirectory"
    const val DIRECTORY_NOT_EMPTY = "DirectoryNotEmpty"
    
    const val PROJECT_NOT_FOUND = "ProjectNotFound"
    
    const val PLUGIN_NOT_FOUND = "PluginNotFound"
    const val PLUGIN_ALREADY_EXISTS = "PluginAlreadyExists"
    const val PLUGIN_ALREADY_ADDED_TO_PROJECT = "PluginAlreadyAddedToProject"
    const val PLUGIN_NOT_ADDED_TO_PROJECT = "PluginNotAddedToProject"
    
    const val USER_NOT_FOUND = "UserNotFound"
    const val INVALID_CREDENTIALS = "InvalidCredentials"
    const val LAST_OWNER = "LastOwner"
    const val OWNER_ALREADY_EXISTS = "OwnerAlreadyExists"
    const val OWNER_NOT_FOUND = "OwnerNotFound"
    
    const val FILE_DATA_CIRCULAR_DEPENDENCY = "FileDataCircularDependency"
    const val FILE_DATA_COMPUTATION_FAILED = "FileDataComputationFailed"
    const val FILE_DATA_NO_PLUGIN_FOUND = "FileDataNoPluginFound"
    
    const val EXECUTION_NOT_FOUND = "ExecutionNotFound"
    const val EXECUTION_ALREADY_COMPLETED = "ExecutionAlreadyCompleted"
    const val EXECUTION_INVALID_STATE = "ExecutionInvalidState"
    const val EXECUTION_PLUGIN_ERROR = "ExecutionPluginError"
    const val PROJECT_LOCKED = "ProjectLocked"
}

fun <T> success(value: T): ApiResult<T> = ApiResult.Success(value)

fun fileSystemFailure(code: String, message: String): ApiResult<Nothing> = 
    ApiResult.Failure(ApiError(code, message))

fun projectFailure(code: String, message: String): ApiResult<Nothing> = 
    ApiResult.Failure(ApiError(code, message))

fun pluginFailure(code: String, message: String): ApiResult<Nothing> = 
    ApiResult.Failure(ApiError(code, message))

fun commonFailure(code: String, message: String): ApiResult<Nothing> = 
    ApiResult.Failure(ApiError(code, message))

fun fileDataFailure(code: String, message: String): ApiResult<Nothing> = 
    ApiResult.Failure(ApiError(code, message))

fun executionFailure(code: String, message: String): ApiResult<Nothing> = 
    ApiResult.Failure(ApiError(code, message))
