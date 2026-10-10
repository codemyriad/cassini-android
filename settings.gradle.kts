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
include(":core")
include(":data")
include(":engine")
include(":ui")
include(":app")
// Device experiments and calibration (com.android.test, instruments :app). Not part of ciTest.
include(":bench")
