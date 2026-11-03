plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The protocol is shared by the execution side (`:script`) and by plugin services answering
    // it, so it depends on nothing but the serialization it is defined in.
    api(libs.kotlinx.serialization.cbor)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
