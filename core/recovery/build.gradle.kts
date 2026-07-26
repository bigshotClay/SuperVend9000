plugins {
    id("vending.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:plan"))
    api(project(":core:llm"))
    implementation(project(":core:validate"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(project(":core:gates"))
}
