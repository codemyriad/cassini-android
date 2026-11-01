import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Storage: the Room catalogue, the DataStore settings and the file repositories (docs/architecture.md, sections 3
// and 4). Every path comes from AppPaths, so tests and device checks can point the whole store at a scratch root.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val fixtures: String = rootProject.file("fixtures").absolutePath

android {
    namespace = "org.cassini.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
                // The repository tests build documents over the committed FLEURS clips. Declaring them as an
                // input, relative to their directory, re-runs the tests when a clip changes. The absolute path
                // goes through an argument provider, so it is not part of the build cache key.
                it.inputs.dir(rootProject.file("fixtures/audio"))
                    .withPropertyName("fixtures")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // A local copy: the lambda must not capture the script object (configuration cache).
                val fixturesPath = fixtures
                it.jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dcassini.fixtures=$fixturesPath") })
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

// Exported schemas are committed; MigrationTestHelper reads them.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core"))
    implementation(libs.bundles.room)
    implementation(libs.annotation)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.bundles.testing.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.org.json)
    // ModelFetcherNetworkTest decompresses real Zstandard files: the plain jar on the JVM.
    testImplementation(libs.zstd.jni)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
