package com.mdeo.optimizer.rulegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The operators generated for `mutate A` and `mutate B` for every pair of end multiplicities, and
 * for a unidirectional end, compared with the reviewed table in `rulegen/operator-table.txt`.
 *
 * Any change to which operators are generated shows up as a difference in this table. After a
 * deliberate change, rewrite the table with `./gradlew :optimizer:test -Prulegen.updateTable=true`
 * and review the diff.
 */
class OperatorTableTest {

    private val bounds = listOf("*", "?", "+", "1", "2..3", "2..*", "1..2", "2")

    @Test
    fun `operators for every pair of multiplicities match the reviewed table`() {
        val table = buildString {
            for (e in bounds) for (f in bounds) row("A.e[$e] <--> B.f[$f]") { assoc("A.e[$e]", "<-->", "B.f[$f]") }
            for (e in bounds) row("A.e[$e] --> B") { assoc("A.e[$e]", "-->", "B") }
        }
        val file = File("src/test/resources/rulegen/operator-table.txt")
        if (System.getProperty("rulegen.updateTable") == "true") file.writeText(table)
        assertEquals(file.readText(), table)
    }

    private fun StringBuilder.row(title: String, association: MetamodelBuilder.() -> Unit) {
        val metamodel = mm("Context", "A", "B") {
            assoc("Context.as[*]", "*-->", "A.ctx")
            assoc("Context.bs[*]", "*-->", "B.ctx")
            association()
        }
        val result = MutationRuleGenerator.generate(metamodel, listOf(spec("mutate A"), spec("mutate B")))
        append(title).append('\n')
        result.mutations.map { it.name }.sorted().forEach { append("    ").append(it).append('\n') }
        result.warnings.forEach { append("    ! ").append(it).append('\n') }
    }
}
