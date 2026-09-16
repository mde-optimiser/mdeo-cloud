plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":expression"))
    implementation(project(":metamodel"))
    implementation(libs.kotlinx.serialization.json)

    // The script-functions session protocol external functions are called over
    api(project(":script-functions-protocol"))
    
    // Apache Commons Collections for Bag implementation
    implementation(libs.commons.collections)
    
    // ASM for bytecode generation
    implementation(libs.asm)
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    // The Kotlin service helper, so client tests run against the real service side
    testImplementation(project(":script-plugin-service"))
}
