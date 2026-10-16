import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates the Baseline Profile of :app (docs/development.md, "Baseline Profile"). The generator drives the
// releaseTest build (release without R8, not debuggable), so the profile holds the real class and method names; R8
// rewrites them when it builds the minified release. Run it with scripts/baseline-profile.sh. Never in CI.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.cassini.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    targetProjectPath = ":app"
    // Macrobenchmark runs in its own process and drives the app from outside.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        // Macrobenchmark needs API 23 and profile collection API 28; without root, collection needs API 33.
        minSdk = 28
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // The same name as the :app build type it drives.
        create("releaseTest") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
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
        lintConfig = rootProject.file("app/lint.xml")
    }
}

androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "releaseTest" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit)
}
