import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// libcassini_opus is compiled from the pinned libopus source that scripts/setup.sh unpacks here.
val opusSource: File = rootDir.resolve(".tools/opus/source")

android {
    namespace = "org.cassini.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
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
        externalNativeBuild {
            cmake {
                // Release flags in every variant: checkpoints store encoder state, so a debug build
                // must encode exactly like a release build.
                arguments += listOf(
                    "-DOPUS_SOURCE=${opusSource.absolutePath}",
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
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
    // The published conformance vectors come from the third_party/cassini-format submodule and are
    // required: a missing checkout fails the tests instead of skipping them.
    val conformanceDirectory = providers.gradleProperty("cassiniConformanceDir")
        .getOrElse(rootDir.resolve("third_party/cassini-format/spec/conformance").absolutePath)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("cassini.conformance", conformanceDirectory)
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.sherpa.onnx.nemotron)
    // Decompresses the speaker model download. The AAR carries the Android native libraries;
    // the JVM unit tests use the jar, which carries the desktop ones.
    implementation("${libs.zstd.jni.get()}@aar")
    testImplementation(libs.zstd.jni)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Without the libopus source CMake fails with an unhelpful error; say what to run instead. A
// prebuilt library left in jniLibs by a 0.1.0 checkout would clash with the one Gradle builds.
val checkOpusSource = tasks.register("checkOpusSource") {
    val marker = opusSource.resolve("CMakeLists.txt")
    val legacyLibraries = file("src/main/jniLibs")
    doLast {
        if (!marker.isFile) {
            throw GradleException("Missing libopus source in ${marker.parentFile}. Run scripts/setup.sh.")
        }
        if (legacyLibraries.walk().any { it.name == "libcassini_opus.so" }) {
            throw GradleException("Delete $legacyLibraries: Gradle now builds libcassini_opus.so from source.")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkOpusSource) }
tasks.matching { it.name.startsWith("configureCMake") }.configureEach { dependsOn(checkOpusSource) }
