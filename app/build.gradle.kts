import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.cassini.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // libcassini_opus is built in :engine; the app's NDK strips the packaged native libraries.
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "org.cassini.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 10
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Library modules cannot filter packaged ABIs; this line must stay in :app.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // debug, or releaseTest for tag builds. The minified release build cannot be instrumented.
    testBuildType = providers.gradleProperty("deviceTestBuildType").getOrElse("debug")
    // Release signing is configured only when CI or a maintainer supplies the keystore and its secrets.
    val releaseKeystore = providers.environmentVariable("CASSINI_BETA_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("beta") {
                fun secret(name: String) = providers.environmentVariable(name).orNull
                    ?: throw GradleException("CASSINI_BETA_KEYSTORE_FILE is set but $name is not.")
                storeFile = file(releaseKeystore)
                storePassword = secret("CASSINI_RELEASE_STORE_PASSWORD")
                keyAlias = secret("CASSINI_RELEASE_KEY_ALIAS")
                keyPassword = secret("CASSINI_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("beta")
        }
        // The release build without R8, for device tests: they call app and Kotlin library members that
        // R8 removes from the minified APK. Tag builds instrument this APK, then install the published
        // release APK over it and check that it launches (scripts/ci-emulator-smoke.sh).
        create("releaseTest") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        lintConfig = file("lint.xml")
        baseline = file("lint-baseline.xml")
    }
    // Captured filesDir trees and audio (fixtures/README.md). The conformance vectors run in :core.
    val fixturesDirectory = rootDir.resolve("fixtures")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgumentProviders.add(
                    TestDataDirectories(
                        fixtures = fileTree(fixturesDirectory),
                        // LicenseAssetsTest compares the APK's copies with these repository files.
                        repository = files(
                            rootDir.resolve("THIRD_PARTY_NOTICES.md"),
                            rootDir.resolve("scripts/native-dependencies.json"),
                            file("src/main/assets/licenses"),
                        ),
                        fixturesPath = fixturesDirectory.absolutePath,
                    ),
                )
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
    bundle { language { enableSplit = false } }
    sourceSets {
        // Device tests read the committed CC-BY-4.0 FLEURS clip from fixtures/audio. Local-only clips
        // (tools/fetch-youtube-audio.sh output in src/androidTest/assets, private meeting clips in
        // tools/private-fixtures) are packaged when present and never committed.
        getByName("androidTest") {
            assets.directories.add(rootDir.resolve("fixtures/audio").path)
            assets.directories.add(rootDir.resolve("tools/private-fixtures").path)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // The native wrappers, libcassini_opus, sherpa-onnx and the zstd-jni AAR (engine/build.gradle.kts).
    implementation(project(":engine"))
    // The JVM unit tests decompress with the zstd-jni jar, which carries the desktop natives.
    testImplementation(libs.zstd.jni)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

/**
 * Passes the fixture directory to the JVM tests as a system property and declares its files as task
 * inputs, relative to the directory, so a changed fixture re-runs the tests instead of leaving them up
 * to date or taking them from the build cache, and the absolute paths stay out of the cache key. The repository files that
 * LicenseAssetsTest reads directly are declared the same way.
 */
class TestDataDirectories(
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val fixtures: FileTree,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val repository: FileCollection,
    @get:Internal val fixturesPath: String,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dcassini.fixtures=$fixturesPath")
}
