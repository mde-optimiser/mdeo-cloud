package com.mdeo.optimizer.rulegen

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The operators for every class of MDEOptimiser's example metamodels ([ExampleMetamodels]) keep
 * random models of them consistent; see [ConsistencyOracle].
 */
class ExampleMetamodelRuleTest {

    @TestFactory
    fun `operators for every class of the example metamodels keep models consistent`(): List<DynamicTest> =
        ExampleMetamodels.all.map { (name, metamodel) ->
            DynamicTest.dynamicTest(name) {
                ConsistencyOracle(
                    metamodel,
                    metamodel.classes.map { spec("mutate ${it.name}") },
                    models = 8,
                    walk = 60,
                    maxPerClass = 4
                ).check()
            }
        }
}
