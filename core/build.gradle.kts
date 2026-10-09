import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The verbatim engine as plain Kotlin/JVM: no android.*, no sherpa-onnx, no coroutines.
// `./gradlew --configure-on-demand :core:test` needs only a JDK and the third_party/cassini-format submodule: no
// Android SDK, NDK, native AAR, libopus source or device. Without --configure-on-demand Gradle also configures :app
// and :engine, which needs the Android SDK and its NDK (docs/development.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Lets :app's lint (checkDependencies) analyze this module too.
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

lint {
    warningsAsErrors = true
    abortOnError = true
    lintConfig = file("lint.xml")
}

dependencies {
    // Android provides org.json at runtime and :app packages the zstd-jni AAR; the JVM tests add the jars.
    compileOnly(libs.org.json)
    compileOnly(libs.zstd.jni)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.zstd.jni)
}

// The published conformance vectors come from the third_party/cassini-format submodule and are required:
// a missing checkout fails the tests instead of skipping them. -PcassiniConformanceDir overrides the location.
val conformanceDirectory: String = providers.gradleProperty("cassiniConformanceDir")
    .getOrElse(rootDir.resolve("third_party/cassini-format/spec/conformance").absolutePath)
// DefaultLabelsTest compares the placeholder prefixes with the app's string resources.
val appResources: File = rootDir.resolve("app/src/main/res")
// SpeakerBudgetTest compares the diarizer state cap with the bound the pinned JNI patch enforces.
val diarizationPatch: File = rootDir.resolve("scripts/streaming-diarization.patch")

tasks.test {
    jvmArgumentProviders.add(
        CoreTestData(
            conformance = fileTree(conformanceDirectory),
            resources = fileTree(appResources) { include("values*/strings.xml") },
            patch = files(diarizationPatch),
            conformancePath = conformanceDirectory,
            resourcesPath = appResources.absolutePath,
            patchPath = diarizationPatch.absolutePath,
        ),
    )
}

/**
 * Passes the test data directories as system properties and declares their files as task inputs, relative to each
 * directory, so a changed submodule pin or a missing checkout re-runs (and fails) the tests instead of leaving them
 * up to date, and absolute paths stay out of the build cache key.
 */
class CoreTestData(
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val conformance: FileTree,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val resources: FileTree,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val patch: FileCollection,
    @get:Internal val conformancePath: String,
    @get:Internal val resourcesPath: String,
    @get:Internal val patchPath: String,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "-Dcassini.conformance=$conformancePath",
        "-Dcassini.appResources=$resourcesPath",
        "-Dcassini.diarizationPatch=$patchPath",
    )
}
