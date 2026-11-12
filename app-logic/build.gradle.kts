plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(libs.coroutines.core)
    testImplementation(libs.bundles.testing.jvm)
}
