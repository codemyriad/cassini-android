plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.roborazzi) apply false
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
            "core/src/main/kotlin",
            "core/src/test/kotlin",
            "data/src/main/kotlin",
            "data/src/test/kotlin",
            "data/src/androidTest/kotlin",
            "engine/src/main/kotlin",
            "engine/src/test/kotlin",
            "ui/src/main/kotlin",
            "ui/src/test/kotlin",
            "ui/src/androidTest/kotlin",
            "tools/asr-eval",
            "build.gradle.kts",
            "settings.gradle.kts",
            "app/build.gradle.kts",
            "core/build.gradle.kts",
            "data/build.gradle.kts",
            "engine/build.gradle.kts",
            "ui/build.gradle.kts",
        ),
    )
    parallel = true
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}
