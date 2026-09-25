package com.mdeo.backend.git

import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription

/**
 * A pack description that remembers its own name.
 *
 * [DfsPackDescription] takes a name in its constructor but exposes no getter
 * for it, only file names derived per extension. The storage layer keys packs
 * by name in the database, so it needs the name back when JGit hands a
 * description to be written, read or deleted. Every description in this backend
 * originates from `newPack` or `listPacks`, both of which build this type.
 *
 * @param repositoryDescription The repository the pack belongs to
 * @param name The pack's name, unique within the repository
 * @param source Where the pack came from, which JGit uses to order lookups
 */
class NamedPackDescription(
    repositoryDescription: DfsRepositoryDescription,
    val name: String,
    source: DfsObjDatabase.PackSource
) : DfsPackDescription(repositoryDescription, name, source)
