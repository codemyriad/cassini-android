import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The native half of the engine: sherpa-onnx wrappers, libcassini_opus, the MediaCodec decoder and microphone
// capture. The pure algorithms they feed live in :core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// libcassini_opus is compiled from the pinned libopus source that scripts/setup.sh unpacks here.
val opusSource: File = rootDir.resolve(".tools/opus/source")

android {
    namespace = "org.cassini.engine"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("proguard-consumer-rules.pro")
        // Limits this module's CMake build. Packaged ABIs are filtered by the same line in :app.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                // Release flags in every variant: the encoder must behave identically in every build
                // (a resumed checkpoint primes a fresh encoder and continues the same stream), so
                // libopus always gets the Release flags (-O3 -DNDEBUG) of the old hand-run build.
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    // The wrappers expose sherpa-onnx types (configs, results) and suspend functions to :app.
    api(libs.sherpa.onnx.nemotron)
    api(libs.coroutines.core)
    // Decompresses the speaker model download. The AAR carries the Android native libraries;
    // the JVM unit tests use the jar, which carries the desktop ones.
    implementation("${libs.zstd.jni.get()}@aar")
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
}

// Without the libopus source CMake fails with an unhelpful error; say what to run instead. A
// prebuilt library left in app/src/main/jniLibs by a 0.1.0 checkout would clash with the one Gradle builds.
val checkOpusSource = tasks.register("checkOpusSource") {
    val marker = opusSource.resolve("CMakeLists.txt")
    val legacyLibraries = rootDir.resolve("app/src/main/jniLibs")
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
