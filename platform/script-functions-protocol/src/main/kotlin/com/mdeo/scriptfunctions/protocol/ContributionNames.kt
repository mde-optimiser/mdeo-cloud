package com.mdeo.scriptfunctions.protocol

/**
 * Names the script runtime and contribution services must agree on, defined once for both.
 */
object ContributionNames {
    /**
     * The package every contributed class lives in, followed by `/<contribution id>`.
     */
    const val CLASS_PACKAGE_PREFIX = "contrib"

    /**
     * The method every record has that copies it with some fields changed. No field may shadow it.
     */
    const val RECORD_COPY_METHOD = "with"

    /**
     * The package the classes of a contribution are referred to by.
     *
     * @param contributionId The contribution id
     * @return `contrib/<contribution id>`
     */
    fun classPackage(contributionId: String): String = "$CLASS_PACKAGE_PREFIX/$contributionId"
}
