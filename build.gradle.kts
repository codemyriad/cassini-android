plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(
        files(
            "app/src/main/java",
            "app/src/test/java",
            "app/src/androidTest/java",
            "build.gradle.kts",
            "settings.gradle.kts",
            "app/build.gradle.kts",
        ),
    )
    parallel = true
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}
