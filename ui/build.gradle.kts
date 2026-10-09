import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Compose design system and the stateless screens (docs/design/ui.md). Depends only on Compose and
// kotlinx-collections-immutable: never on :core, :data, :engine or :app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "org.cassini.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // Every :ui resource name starts with ui_, so :app resources can never silently override them.
    resourcePrefix = "ui_"
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // The committed goldens: a changed golden re-runs verifyRoborazziDebug instead of leaving it up to date.
                it.inputs.files(fileTree("src/test/screenshots"))
                    .withPropertyName("screenshotGoldens")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // Robolectric needs these on JDK 17.
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                )
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Goldens are committed next to the tests. :ui:recordRoborazziDebug updates them, :ui:verifyRoborazziDebug
// (through the root ciTest task) compares with them, :ui:compareRoborazziDebug writes diffs to build/outputs/roborazzi.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    // UiState classes expose ImmutableList (docs/design/ui.md, section 2), so :app ViewModels need the type.
    api(libs.kotlinx.collections.immutable)
    // Android Studio previews. It also adds the exported PreviewActivity to the debug APK of :app.
    debugImplementation(libs.compose.ui.tooling)
    // Provides the activity that createComposeRule() launches in the Robolectric tests. testImplementation, not
    // debugImplementation, so its exported test activity does not reach the debug APK of :app.
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.bundles.testing.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
