package com.mdeo.metamodel.data

/**
 * Loads the metamodel at [path] together with every metamodel file it imports, directly or
 * through other imports: a model of the metamodel may hold instances of the classes of all of
 * them. See [merge] for the result.
 *
 * @param path The absolute path of the metamodel file.
 * @param fetch Fetches the data of one metamodel file, or returns `null` when it has none, for
 *   example because the file does not exist or has errors.
 * @return The merged metamodel, or `null` when a file could not be fetched.
 */
suspend fun MetamodelData.Companion.load(
    path: String,
    fetch: suspend (path: String) -> MetamodelData?
): MetamodelData? {
    val files = LinkedHashMap<String, MetamodelData>()
    val pending = ArrayDeque(listOf(path))
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next in files) continue
        val data = fetch(next) ?: return null
        files[next] = data
        pending.addAll(data.importedMetamodelPaths)
    }
    return merge(files.values.first(), files.values.drop(1))
}

/**
 * Merges a metamodel file with the files it imports into one metamodel. The result keeps the
 * path and imports of [main] and has the classes, enums and associations of all files; each
 * class and enum of an imported file names that file in its `path`. Names stay unique, since the
 * metamodel language rejects an import that brings in a second class or enum of the same name.
 *
 * @param main The metamodel file.
 * @param imported The files [main] imports, directly or not, each once.
 */
fun MetamodelData.Companion.merge(main: MetamodelData, imported: List<MetamodelData>): MetamodelData {
    if (imported.isEmpty()) return main
    return main.copy(
        classes = main.classes + imported.flatMap { file -> file.classes.map { it.copy(path = file.pathOf(it)) } },
        enums = main.enums + imported.flatMap { file -> file.enums.map { it.copy(path = file.pathOf(it)) } },
        associations = main.associations + imported.flatMap { it.associations }
    )
}
