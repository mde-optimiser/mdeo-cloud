package com.mdeo.metamodel.csv

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CsvModelInferenceManualRun {

    @Test
    fun `print inferred metamodel and model for a real csv file`() {
        val csvPath = Path.of("/Users/amirguliyev/Downloads/employees.csv")
        val csvText = csvPath.toFile().readText()

        val className = csvPath.fileName.toString().removeSuffix(".csv")

        val result = CsvModelInference.inferFromCsv(
            csvText = csvText,
            className = className,
            metamodelPath = "/$className.metamodel"
        )

        val json = Json { prettyPrint = true }

        println("===== METAMODEL =====")
        println(json.encodeToString(result.metamodel))

        println("===== MODEL =====")
        println(json.encodeToString(result.model))

        if (result.warnings.isNotEmpty()) {
            println("===== WARNINGS =====")
            result.warnings.forEach { println(it) }
        }
    }
}
