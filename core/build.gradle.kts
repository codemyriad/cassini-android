import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The verbatim engine as plain Kotlin/JVM: no android.*, no sherpa-onnx, no coroutines. `./gradlew :core:test`
// needs a JDK, but no NDK, native AAR, libopus source or device (docs/development.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
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
    // FixtureTree, shared with the Android modules' JVM tests.
    testFixturesImplementation(libs.junit)
}

// The published conformance vectors come from the third_party/cassini-format submodule and are required:
// a missing checkout fails the tests instead of skipping them. -PcassiniConformanceDir overrides the location.
val conformanceDirectory: String = providers.gradleProperty("cassiniConformanceDir")
    .getOrElse(rootDir.resolve("third_party/cassini-format/spec/conformance").absolutePath)
// Captured filesDir trees (fixtures/README.md).
val fixturesDirectory: File = rootDir.resolve("fixtures")
// DefaultLabelsTest compares the placeholder prefixes with the app's string resources.
val appResources: File = rootDir.resolve("app/src/main/res")

tasks.test {
    jvmArgumentProviders.add(
        CoreTestData(
            conformance = fileTree(conformanceDirectory),
            fixtures = fileTree(fixturesDirectory),
            resources = fileTree(appResources) { include("values*/strings.xml") },
            conformancePath = conformanceDirectory,
            fixturesPath = fixturesDirectory.absolutePath,
            resourcesPath = appResources.absolutePath,
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
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val fixtures: FileTree,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val resources: FileTree,
    @get:Internal val conformancePath: String,
    @get:Internal val fixturesPath: String,
    @get:Internal val resourcesPath: String,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "-Dcassini.conformance=$conformancePath",
        "-Dcassini.fixtures=$fixturesPath",
        "-Dcassini.appResources=$resourcesPath",
    )
}
