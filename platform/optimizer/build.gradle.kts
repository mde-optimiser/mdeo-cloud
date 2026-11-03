plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":expression"))
    implementation(project(":metamodel"))
    implementation(project(":model-transformation"))
    implementation(project(":script"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutines.core)

    // Apache TinkerPop Gremlin
    implementation(libs.gremlin.core)
    implementation(libs.tinkergraph.gremlin)

    // MOEA Framework
    implementation(libs.moeaframework)

    // Logging
    implementation(libs.logback)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
}

tasks.test {
    // -Prulegen.updateTable=true rewrites the reviewed table of generated operators (OperatorTableTest).
    (findProperty("rulegen.updateTable") as? String)?.let { systemProperty("rulegen.updateTable", it) }
    // When asyncProfilerLib is set (e.g. -PasyncProfilerLib=.../libasyncProfiler.so),
    // attach the agent at JVM startup and dump collapsed stacks to asyncProfilerOutput.
    // asyncProfilerEvent controls the event type (cpu, alloc, live, ...).
    // The shell script converts the .collapsed file to an interactive HTML flame graph.
    val profilerLib = findProperty("asyncProfilerLib") as? String
    if (profilerLib != null) {
        val outputFile = (findProperty("asyncProfilerOutput") as? String)
            ?: "${rootProject.rootDir}/tools/profile.collapsed"
        val event = (findProperty("asyncProfilerEvent") as? String) ?: "cpu"
        val agentEvent = if (event == "live") "alloc,live" else event
        jvmArgs("-agentpath:$profilerLib=start,event=$agentEvent,interval=1000000,file=$outputFile")
    }
}

// Prints the test runtime classpath so ad-hoc benchmark drivers can be launched with `java -cp`.
tasks.register("printTestClasspath") {
    val cp = sourceSets["test"].runtimeClasspath
    doLast { println(cp.asPath) }
}
