package com.mdeo.scriptexecution.service

import com.mdeo.script.ast.TypedAst
import org.slf4j.LoggerFactory

/**
 * Resolves and fetches typed-ast dependencies recursively.
 * 
 * When a typed-ast has imports from other files, this resolver:
 * 1. Identifies the imported file URIs from the imports field
 * 2. Recursively fetches typed-asts for all dependencies
 * 3. Builds a complete map of filePath -> TypedAst for compilation
 * 
 * The typed-ast structure includes an `imports` array where each import has:
 * - `name`: The imported symbol name (local name in the importing file)
 * - `ref`: The referenced function name in the source file
 * - `uri`: The URI of the file containing the imported function
 * 
 * Dependencies are tracked to avoid infinite loops from circular imports.
 */
class TypedAstDependencyResolver(
    private val backendApiService: BackendApiService
) {
    private val logger = LoggerFactory.getLogger(TypedAstDependencyResolver::class.java)
    
    /**
     * Resolves a typed-ast and all its dependencies recursively.
     * 
     * @param projectId UUID of the project
     * @param filePath The main file path to resolve
     * @param jwtToken JWT token for backend authentication
     * @return Map of file URIs to their typed-asts, or null if any fetch fails
     */
    suspend fun resolveWithDependencies(
        projectId: String,
        filePath: String,
        jwtToken: String
    ): Map<String, TypedAst>? {
        // One request for the file and everything it imports; older script services do not offer it.
        backendApiService.getTypedAstClosure(projectId, filePath, jwtToken)?.let { closure ->
            logger.info("Resolved ${closure.size} file(s) including dependencies for $filePath in one request")
            // The closure names every file by its absolute path; the main file is looked up by the
            // path the execution was given, which may lack the leading slash.
            val absolutePath = "/" + filePath.removePrefix("/")
            return closure.mapKeys { (path, _) -> if (path == absolutePath) filePath else path }
        }

        val resolvedAsts = mutableMapOf<String, TypedAst>()
        val visited = mutableSetOf<String>()
        val pending = mutableSetOf(filePath)
        
        while (pending.isNotEmpty()) {
            val currentPath = pending.first()
            pending.remove(currentPath)
            
            if (visited.contains(currentPath)) {
                continue
            }
            
            visited.add(currentPath)
            
            val typedAst = backendApiService.getTypedAst(projectId, currentPath, jwtToken)
            if (typedAst == null) {
                logger.error("Failed to fetch typed-ast for: $currentPath")
                return null
            }
            
            resolvedAsts[currentPath] = typedAst
            
            val dependencies = typedAst.imports.map { it.uri }.distinct()
            
            if (dependencies.isNotEmpty()) {
                logger.info("Found ${dependencies.size} dependencies for $currentPath: $dependencies")
            }
            
            for (depUri in dependencies) {
                if (!visited.contains(depUri) && !pending.contains(depUri)) {
                    pending.add(depUri)
                }
            }
        }
        
        logger.info("Resolved ${resolvedAsts.size} file(s) including dependencies for $filePath")
        return resolvedAsts
    }
}
