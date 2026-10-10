import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Device experiments and calibration (docs/architecture.md section 2): measurements that instrument the debug build
// of :app on a device and use the models the app installed. Never in CI: the root ciTest task selects only
// application and library modules. Run with scripts/device-smoke.sh --bench or scripts/diarization-poc.sh.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.cassini.bench"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = rootProject.file("app/lint.xml")
    }
    sourceSets {
        // The committed FLEURS clip, the local-only YouTube clips (tools/fetch-youtube-audio.sh) and the private
        // meeting clips of VoiceprintCalibrationTest (tools/private-fixtures, never committed).
        getByName("main") {
            assets.directories.add(rootDir.resolve("fixtures/audio").path)
            assets.directories.add(rootDir.resolve("app/src/androidTest/assets").path)
            assets.directories.add(rootDir.resolve("tools/private-fixtures").path)
        }
    }
}

// Only the debug build: the minified release build of :app cannot be instrumented.
androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The tested app's own classes are on the compile classpath; its modules are not. compileOnly: at run time the
    // classes come from the app's APK, which the bench instruments in the same process.
    compileOnly(project(":core"))
    compileOnly(project(":engine"))
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
