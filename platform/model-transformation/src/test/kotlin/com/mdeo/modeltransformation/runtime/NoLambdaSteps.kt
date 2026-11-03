package com.mdeo.modeltransformation.runtime

import com.mdeo.modeltransformation.graph.mdeo.MdeoGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.step.LambdaHolder
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Rejects every lambda step ([LambdaHolder]); TinkerPop applies strategies to the child
 * traversals too. TinkerPop's `LambdaRestrictionStrategy` only rejects steps whose description
 * contains "lambda", which misses Kotlin lambdas. The engine compiles to pure Gremlin, so that it
 * also runs against remote graph databases.
 */
class NoLambdaSteps :
    AbstractTraversalStrategy<TraversalStrategy.VerificationStrategy>(),
    TraversalStrategy.VerificationStrategy {
    override fun apply(traversal: Traversal.Admin<*, *>) {
        for (step in traversal.steps) {
            if (step is LambdaHolder) throw VerificationException("lambda step $step", traversal)
        }
    }
}

/**
 * With `MDEO_NO_LAMBDA_STEPS=1` (Gradle then enables JUnit's extension autodetection, see
 * `build.gradle.kts`), installs [NoLambdaSteps] for every TinkerGraph and MdeoGraph traversal of
 * the test JVM, so that the whole suite checks that the engine emits no lambda step.
 */
class NoLambdaStepsEverywhere : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) = install()

    companion object {
        private var installed = false

        @Synchronized
        fun install() {
            if (installed || System.getenv("MDEO_NO_LAMBDA_STEPS") != "1") return
            // the graphs register their own strategies when their classes initialise: first that
            for (graphClass in listOf(MdeoGraph::class.java, TinkerModelGraph::class.java)) {
                Class.forName(graphClass.name, true, graphClass.classLoader)
            }
            for (graphClass in listOf(Graph::class.java, TinkerGraph::class.java, MdeoGraph::class.java)) {
                TraversalStrategies.GlobalCache.registerStrategies(
                    graphClass,
                    TraversalStrategies.GlobalCache.getStrategies(graphClass).clone().addStrategies(NoLambdaSteps())
                )
            }
            installed = true
        }
    }
}
