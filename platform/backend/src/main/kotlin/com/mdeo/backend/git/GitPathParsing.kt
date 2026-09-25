package com.mdeo.backend.git

import java.util.UUID

/**
 * Extracts the project id from a path a git client names a repository by.
 *
 * Used by both [com.mdeo.backend.routes.gitRoutes] (an HTTP route parameter,
 * which never has a leading slash) and the SSH command factory (an `exec`
 * command argument like `git-upload-pack '/<id>.git'`, which does) - a
 * leading slash and a trailing `.git` are both optional and stripped if
 * present, so either caller can pass its raw path straight through.
 *
 * @return The parsed project id, or null if the path is not a valid UUID once stripped
 */
fun parseProjectIdFromGitPath(path: String): UUID? {
    return path
        .removePrefix("/")
        .removeSuffix(".git")
        .let { runCatching { UUID.fromString(it) }.getOrNull() }
}
