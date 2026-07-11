package com.mdeo.metamodel.csv

import com.mdeo.metamodel.data.MetamodelData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CsvModelInferenceManualRun {

    @Test
    @Disabled("Manual run only (requires local CSV and metamodel files).")
    fun `import csv onto existing metamodel`() {
        val csvPath = Path.of("/Users/amirguliyev/Downloads/employees.csv")
        val metamodelPath = Path.of("/Users/amirguliyev/Downloads/employees.metamodel")

        val csvText = csvPath.toFile().readText()
        val metamodel = Json.decodeFromString<MetamodelData>(metamodelPath.toFile().readText())

        val className = csvPath.fileName.toString().removeSuffix(".csv")
            .replaceFirstChar { it.uppercaseChar() }

        val result = CsvModelInference.importFromCsv(
            csvText = csvText,
            className = className,
            metamodel = metamodel,
            metamodelPath = metamodelPath.toString()
        )

        val json = Json { prettyPrint = true }

        println("===== MODEL =====")
        println(json.encodeToString(result.model))

        if (result.warnings.isNotEmpty()) {
            println("===== WARNINGS =====")
            result.warnings.forEach { println(it) }
        }
    }
}
