plugins {
    id("vending.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:state"))
    implementation(project(":core:llm"))
    implementation(project(":sim"))
    implementation(project(":core:plan"))
    implementation(project(":core:gates"))
    implementation(project(":core:validate"))
    implementation(project(":personas"))
    implementation(project(":core:orchestrator"))
    implementation(project(":core:pricing"))
    implementation(project(":core:procure"))
    implementation(project(":reports"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.supervend.app.MainKt")
}
