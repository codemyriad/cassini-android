plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.cassini.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.cassini.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.0.6-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    testBuildType = providers.gradleProperty("deviceTestBuildType").getOrElse("debug")
    val betaKeystore = providers.environmentVariable("CASSINI_BETA_KEYSTORE_FILE").orNull
    signingConfigs {
        if (betaKeystore != null) create("beta") {
            storeFile = file(betaKeystore)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (betaKeystore != null) signingConfig = signingConfigs.getByName("beta")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val conformanceDirectory = providers.gradleProperty("cassiniConformanceDir")
        .getOrElse(rootProject.projectDir.parentFile.resolve("cassini-format/spec/conformance").absolutePath)
    val requireConformance = providers.gradleProperty("requireCassiniConformance").getOrElse("false")
    testOptions { unitTests.all {
        it.systemProperty("cassini.conformance", conformanceDirectory)
        it.systemProperty("cassini.conformance.required", requireConformance)
    } }
    bundle { language { enableSplit = false } }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
