plugins {
    id("vending.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // The sim meets the harness only at the EnvironmentPort boundary; it shares the domain
    // vocabulary (Cents, ProductId, ...) from core:model but depends on no other core module.
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
}
