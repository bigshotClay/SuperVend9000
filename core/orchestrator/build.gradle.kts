plugins {
    id("vending.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:plan"))
    api(project(":core:recovery"))
    api(project(":core:gates"))
    api(project(":core:validate"))
    api(project(":core:llm"))
    api(project(":personas"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
}

// Forward the golden-file switches to the test JVM (Gradle does not propagate -D by default).
// Unset ⇒ empty ⇒ the golden check runs in assert mode (the CI default).
tasks.test {
    systemProperty("golden.write", System.getProperty("golden.write") ?: "")
    System.getProperty("golden.dir")?.let { systemProperty("golden.dir", it) }
}
