pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The patched sherpa-onnx AAR. scripts/setup.sh installs it here; native/m2/README.md explains how.
        maven {
            url = uri("native/m2")
            content { includeGroup("org.cassini.native") }
        }
    }
}
rootProject.name = "CassiniAndroid"
// Static checks (:detekt) run on the root project over explicit source paths in build.gradle.kts;
// skipping subproject inclusion avoids configuring AGP or touching the Android SDK/NDK.
val staticChecksOnly = providers.gradleProperty("cassiniStaticChecksOnly")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
if (!staticChecksOnly) {
    include(":core")
    include(":data")
    include(":engine")
    include(":ui")
    include(":app")
    // Device experiments and calibration (com.android.test, instruments :app). Not part of ciTest.
    include(":bench")
    // Generates app/src/main/baseline-prof.txt on a device (com.android.test, scripts/baseline-profile.sh). Not part of ciTest.
    include(":baselineprofile")
}
